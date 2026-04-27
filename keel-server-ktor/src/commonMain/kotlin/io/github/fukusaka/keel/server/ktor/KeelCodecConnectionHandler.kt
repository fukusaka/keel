package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpParseException
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.codec.http.addHttp1ServerCodec
import io.github.fukusaka.keel.codec.http.writeResponseHead
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import io.ktor.util.pipeline.execute
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

/**
 * [KtorConnectionHandler] backed by keel's [HttpRequestDecoder][io.github.fukusaka.keel.codec.http.HttpRequestDecoder]
 * / [HttpResponseEncoder][io.github.fukusaka.keel.codec.http.HttpResponseEncoder] /
 * [HttpBodyAggregator][io.github.fukusaka.keel.codec.http.HttpBodyAggregator] codec stack from `:keel-codec-http`.
 *
 * Installs the standard HTTP/1.1 server-side codec via
 * [addHttp1ServerCodec][io.github.fukusaka.keel.codec.http.addHttp1ServerCodec],
 * appends a [SuspendMessageBridge] to expose the parsed [HttpRequest]
 * stream to the suspend keep-alive loop, builds a [KeelApplicationCall]
 * per request, and dispatches through `engine.pipeline.execute(call)`.
 *
 * **Pipeline shape** (per accepted connection):
 * ```
 * HEAD ↔ [tls] ↔ HttpResponseEncoder ↔ HttpRequestDecoder
 *      ↔ HttpBodyAggregator ↔ SuspendMessageBridge<HttpRequest> ↔ TAIL
 * ```
 *
 * Inbound: decoder parses raw `IoBuf` into streaming HTTP messages, the
 * aggregator reassembles them into [HttpRequest], the bridge delivers them
 * to this loop via [SuspendMessageBridge.receiveCatching].
 *
 * Outbound: [KeelApplicationResponse] emits
 * [HttpResponseHead][io.github.fukusaka.keel.codec.http.HttpResponseHead] /
 * [HttpBody][io.github.fukusaka.keel.codec.http.HttpBody] /
 * [HttpBodyEnd][io.github.fukusaka.keel.codec.http.HttpBodyEnd] via the
 * pipeline, the encoder serialises to wire-format `IoBuf`s.
 *
 * Keep-alive: when enabled in [KeelApplicationEngine.Configuration.keepAlive],
 * processes multiple sequential requests on the same TCP connection until
 * the client sends `Connection: close`, an error occurs, or the connection
 * is closed by the peer.
 */
internal class KeelCodecConnectionHandler : KtorConnectionHandler {

    override suspend fun handle(
        channel: PipelinedChannel,
        scheme: String,
        engine: KeelApplicationEngine,
        scope: CoroutineScope,
    ) {
        // Install pipeline HTTP codec: inbound: decoder → aggregator → bridge delivers
        // HttpRequest to this suspend loop. Outbound: KeelApplicationResponse emits
        // HttpResponseHead/HttpBody/HttpBodyEnd → encoder serialises to IoBuf.
        val bridge = SuspendMessageBridge(HttpRequest::class)
        channel.addHttp1ServerCodec()
        channel.pipeline.addLast("bridge", bridge)

        // Arm the read loop. SuspendMessageBridge serves as the pipeline-
        // level bridge (no SuspendBridgeHandler needed). Only readEnabled
        // is required to start delivering data to the pipeline.
        withContext(channel.ioDispatcher) {
            channel.readEnabled = true
        }

        try {
            val configuration = engine.configuration
            val serverKeepAlive = configuration.keepAlive

            while (channel.isActive) {
                val result = bridge.receiveCatching()
                if (result.isClosed) {
                    // EOF or parse error from the pipeline.
                    val cause = result.exceptionOrNull()
                    if (cause is HttpParseException) {
                        respondBadRequest(channel)
                    }
                    break
                }
                val request = result.getOrThrow()

                val keepAlive = serverKeepAlive && request.isKeepAlive

                // Body is already aggregated by HttpBodyAggregator into ByteArray.
                val bodyBytes = request.body
                val requestBody: ByteReadChannel = if (bodyBytes != null) {
                    ByteReadChannel(bodyBytes)
                } else {
                    ByteReadChannel.Empty
                }

                val head = HttpRequestHead(
                    request.method,
                    request.uri,
                    request.version,
                    request.headers,
                )

                val call = KeelApplicationCall(
                    application = engine.application(),
                    head = head,
                    localAddress = channel.localAddress,
                    remoteAddress = channel.remoteAddress,
                    requestBody = requestBody,
                    pipelinedChannel = channel,
                    scope = scope,
                    coroutineContext = scope.coroutineContext,
                    keepAlive = keepAlive,
                    scheme = scheme,
                )

                // Run the Ktor pipeline on the configured application
                // dispatcher — null (default) means channel.ioDispatcher,
                // i.e. the EventLoop driving native I/O, so the entire
                // request runs on one thread with no cross-thread hop.
                // A non-null configuration.applicationDispatcher (e.g.
                // Dispatchers.Default) offloads the pipeline onto a
                // separate pool, costing one hop per request but
                // absorbing blocking handlers in that pool instead of
                // on the EventLoop. The ReferenceEquals check short-
                // circuits the withContext when we are already on the
                // target dispatcher — the common case after
                // receiveCatching() resumed us on the EventLoop thread
                // and no applicationDispatcher is configured.
                val appCtx = configuration.applicationDispatcher ?: channel.ioDispatcher
                if (appCtx !== scope.coroutineContext[ContinuationInterceptor]) {
                    withContext(appCtx) { engine.pipeline.execute(call) }
                } else {
                    engine.pipeline.execute(call)
                }

                if (!keepAlive) break
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                engine.logger.error(e) { "Connection handling failed" }
            }
        } finally {
            runCatching { channel.close() }
        }
    }

    /**
     * Sends an HTTP 400 Bad Request response before closing the connection.
     *
     * Uses a temporary [BufferedSuspendSink] to write directly, bypassing
     * the pipeline codec which may be in an inconsistent state after a
     * parse error. Uses HTTP/1.0 to avoid implying keep-alive support,
     * following the same approach as Ktor CIO's error response handling.
     */
    private suspend fun respondBadRequest(channel: PipelinedChannel) {
        try {
            val sink = BufferedSuspendSink(
                channel.asSuspendSink(),
                channel.allocator,
                channel.supportsDeferredFlush,
            )
            val headers = HttpHeaders()
            headers.add(HttpHeaderName.CONNECTION, "close")
            headers.add(HttpHeaderName.CONTENT_LENGTH, "0")
            writeResponseHead(HttpStatus.BAD_REQUEST, HttpVersion.HTTP_1_0, headers, sink)
            sink.flush()
            sink.close()
        } catch (_: Exception) {
            // Best-effort: client may have already disconnected
        }
    }
}
