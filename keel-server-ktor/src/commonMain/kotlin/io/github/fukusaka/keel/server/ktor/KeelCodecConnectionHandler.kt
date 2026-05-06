package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMessage
import io.github.fukusaka.keel.codec.http.HttpParseException
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.codec.http.addHttp1ServerCodec
import io.github.fukusaka.keel.codec.http.writeResponseHead
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import io.github.fukusaka.keel.server.ktor.websocket.WsRoutesAttributeKey
import io.github.fukusaka.keel.server.ktor.websocket.isWebSocketUpgrade
import io.github.fukusaka.keel.server.ktor.websocket.runWebSocketUpgrade
import io.ktor.util.pipeline.execute
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.discard
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

/**
 * [KtorConnectionHandler] backed by keel's [HttpRequestDecoder][io.github.fukusaka.keel.codec.http.HttpRequestDecoder]
 * / [HttpResponseEncoder][io.github.fukusaka.keel.codec.http.HttpResponseEncoder] codec stack from `:keel-codec-http`.
 *
 * Installs the HTTP/1.1 server-side codec with `aggregateBody = false`, appends a
 * [SuspendMessageBridge] to expose the parsed [HttpMessage] stream to the suspend keep-alive
 * loop.  Request body chunks ([HttpBody] / [HttpBodyEnd]) are streamed into a [ByteChannel] via
 * a per-request pump coroutine, so large uploads never require a full-body `ByteArray` peak
 * allocation.
 *
 * **Pipeline shape** (per accepted connection):
 * ```
 * HEAD ↔ [tls] ↔ HttpResponseEncoder ↔ HttpRequestDecoder ↔ SuspendMessageBridge<HttpMessage> ↔ TAIL
 * ```
 *
 * Inbound message sequence per request:
 * ```
 * HttpRequestHead  →  HttpBody*  →  HttpBodyEnd
 * ```
 *
 * **Concurrency invariant**: the keep-alive loop and the per-request body pump never call
 * `bridge.receiveCatching()` concurrently.  The loop reads [HttpRequestHead], then hands off
 * to the pump; after `pumpJob.join()` returns all body messages have been consumed and the
 * loop may read the next head.
 *
 * **WebSocket upgrade**: detected on [HttpRequestHead] before the pump is created.
 * [drainBodyMessages] discards the empty body terminator, then [runWebSocketUpgrade] hijacks
 * the codec stack for the duration of the connection.
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
        // Install codec without body aggregation: inbound flow is
        // HttpRequestHead → HttpBody* → HttpBodyEnd per request.
        val bridge = SuspendMessageBridge(HttpMessage::class)
        channel.addHttp1ServerCodec(aggregateBody = false)
        channel.pipeline.addLast("bridge", bridge)

        // Arm the read loop.
        withContext(channel.ioDispatcher) {
            channel.readEnabled = true
        }

        try {
            val configuration = engine.configuration
            val serverKeepAlive = configuration.keepAlive

            while (channel.isActive) {
                val headResult = bridge.receiveCatching()
                if (headResult.isClosed) {
                    val cause = headResult.exceptionOrNull()
                    if (cause is HttpParseException) {
                        respondBadRequest(channel)
                    }
                    break
                }
                // The first message of every request must be HttpRequestHead.
                // Anything else means the per-request pump terminated before
                // consuming HttpBodyEnd (e.g. cancellation), leaving body chunks
                // queued in the bridge.  Release the orphan IoBuf and tear down
                // the connection — bridge state is no longer aligned with the
                // wire and we cannot safely continue.
                val headMsg = headResult.getOrThrow()
                val head = headMsg as? HttpRequestHead ?: run {
                    if (headMsg is HttpBody) headMsg.content.release()
                    break
                }

                // WebSocket upgrade interception: before creating the body pump,
                // check if this request targets a registered WebSocket route.
                // Drain the zero-byte HttpBodyEnd emitted for GET-style upgrade
                // requests, then hand off to runWebSocketUpgrade which hijacks
                // the codec stack for the duration of the connection.
                if (head.isWebSocketUpgrade()) {
                    val routes = engine.application().attributes.getOrNull(WsRoutesAttributeKey)
                    val handler = routes?.lookup(head.uri)
                    if (handler != null) {
                        drainBodyMessages(bridge)
                        runWebSocketUpgrade(channel, head, scope, handler)
                        break
                    }
                }

                val keepAlive = serverKeepAlive && head.isKeepAlive
                if (!processRequest(head, bridge, channel, engine, scope, scheme, keepAlive)) break
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
     * Executes one HTTP request cycle: streams the body, runs the Ktor pipeline, and waits
     * for any upgrade session to complete. Returns `true` if the keep-alive loop should
     * continue with the next request, or `false` if the connection should be closed.
     *
     * Extracted from [handle] to keep that function's cyclomatic complexity under the project
     * threshold — the `appCtx` dispatcher branch, the upgrade-job join, and the keep-alive
     * termination check all live here rather than inline in the loop.
     */
    private suspend fun processRequest(
        head: HttpRequestHead,
        bridge: SuspendMessageBridge<HttpMessage>,
        channel: PipelinedChannel,
        engine: KeelApplicationEngine,
        scope: CoroutineScope,
        scheme: String,
        keepAlive: Boolean,
    ): Boolean {
        val bodyPipe = ByteChannel(autoFlush = true)
        val pumpJob = scope.launch { pumpBodyIntoChannel(bridge, bodyPipe) }
        val call = KeelApplicationCall(
            application = engine.application(),
            head = head,
            localAddress = channel.localAddress,
            remoteAddress = channel.remoteAddress,
            requestBody = bodyPipe,
            pipelinedChannel = channel,
            scope = scope,
            coroutineContext = scope.coroutineContext,
            keepAlive = keepAlive,
            scheme = scheme,
        )
        // Run the Ktor pipeline on the configured application dispatcher, then drain
        // any unread request body so the pump can reach HttpBodyEnd and the bridge is
        // ready for the next head. A null applicationDispatcher means channel.ioDispatcher
        // (EventLoop thread); the ReferenceEquals short-circuit avoids a withContext hop
        // when we are already on the target dispatcher — the common case after
        // receiveCatching() resumed us on the EventLoop thread.
        try {
            val appCtx = engine.configuration.applicationDispatcher ?: channel.ioDispatcher
            if (appCtx !== scope.coroutineContext[ContinuationInterceptor]) {
                withContext(appCtx) { engine.pipeline.execute(call) }
            } else {
                engine.pipeline.execute(call)
            }
        } finally {
            runCatching { bodyPipe.discard() }
            pumpJob.join()
        }
        // K38: Ktor's ByteWriteChannel.use{} calls the deprecated non-suspend close(),
        // which dispatches the HttpBodyEnd terminator as a fire-and-forget EventLoop task.
        // If the next request is already buffered in the bridge, bridge.receiveCatching()
        // would return synchronously — the keep-alive loop would write the next response's
        // HttpResponseHead to the shared encoder before the terminator task runs, causing
        // the encoder's check(streamingMode == NONE) to throw and the connection to close
        // with the previous response body incomplete. Awaiting here ensures the terminator
        // has been written before we advance to the next request.
        call.response.awaitWriteComplete()
        // K38b: if the streaming write channel was terminated via cancel() (e.g. a client
        // disconnection during SSE) the HttpBodyEnd terminator was never written, leaving the
        // encoder's streamingMode == CHUNKED. Advancing to the next keep-alive request would
        // trigger the encoder's check(streamingMode == NONE). Close the connection instead.
        if (call.response.writeChannelCancelled) return false
        // A protocol upgrade (e.g. WebSocket via respondUpgrade) was performed: the codec
        // was swapped and the upgrade session is running. Join it to keep the connection
        // alive until the peer closes, then signal the loop to exit.
        val upgradeJob = call.response.upgradeJob
        if (upgradeJob != null) {
            upgradeJob.join()
            return false
        }
        return keepAlive
    }

    /**
     * Pumps [HttpBody] / [HttpBodyEnd] messages from [bridge] into [pipe] until the terminal
     * [HttpBodyEnd] is received.  Releases each [IoBuf][io.github.fukusaka.keel.buf.IoBuf] after
     * copying its bytes.  Always closes [pipe] in the `finally` block so the reader observes EOF.
     *
     * Runs as a coroutine launched by the keep-alive loop; the loop must call
     * `pumpJob.join()` before reading the next [HttpRequestHead] from [bridge].
     */
    private suspend fun pumpBodyIntoChannel(
        bridge: SuspendMessageBridge<HttpMessage>,
        pipe: ByteChannel,
    ) {
        var done = false
        try {
            while (!done) {
                val result = bridge.receiveCatching()
                if (result.isClosed) return
                when (val msg = result.getOrThrow()) {
                    is HttpBodyEnd -> {
                        // Copy bytes out and release the IoBuf BEFORE writeFully
                        // so a writer-side exception can't leak the buffer.
                        val bytes = readAndRelease(msg.content)
                        done = true
                        if (bytes != null) pipe.writeFully(bytes)
                    }
                    is HttpBody -> {
                        val bytes = readAndRelease(msg.content)
                        if (bytes != null) pipe.writeFully(bytes)
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            if (e !is CancellationException && !done) runCatching { drainBodyMessages(bridge) }
            if (e is CancellationException) throw e
        } finally {
            pipe.close()
        }
    }

    /**
     * Drains and releases all [HttpBody] / [HttpBodyEnd] messages from [bridge] for the current
     * request without delivering them to any consumer.  Used by the WebSocket upgrade path (where
     * the upgrade request carries no body) and by [pumpBodyIntoChannel]'s error recovery path.
     */
    private suspend fun drainBodyMessages(bridge: SuspendMessageBridge<HttpMessage>) {
        while (true) {
            val result = bridge.receiveCatching()
            if (result.isClosed) return
            when (val msg = result.getOrThrow()) {
                is HttpBodyEnd -> {
                    msg.content.release()
                    return
                }
                is HttpBody -> msg.content.release()
                else -> {}
            }
        }
    }

    /**
     * Copies the readable bytes out of [content] into a new [ByteArray], releases the buffer,
     * and returns the array (or `null` for an empty buffer).  Releasing before the array is
     * handed to a writer prevents leaks if the writer throws.
     */
    private fun readAndRelease(content: IoBuf): ByteArray? {
        val n = content.readableBytes
        if (n == 0) {
            content.release()
            return null
        }
        val bytes = ByteArray(n)
        content.readByteArray(bytes, 0, n)
        content.release()
        return bytes
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
