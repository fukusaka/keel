package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.addHttp1ClientCodec
import io.github.fukusaka.keel.codec.http.materializeReleasingHeaders
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * A single client connection with its HTTP/1.1 codec and response bridge
 * installed once and kept for the connection's lifetime.
 *
 * Unlike a one-request-per-connection path (which builds the codec + bridge
 * per request and closes the connection afterward), a [ClientConnection] can
 * serve multiple request/response cycles — the response decoder is a streaming,
 * one-per-connection handler that resets between responses. This is the
 * unit a connection pool leases and returns for keep-alive reuse.
 *
 * **Serial use only**: HTTP/1.1 has no multiplexing, so [exchange] must not
 * be called concurrently on the same connection — one exchange must fully
 * complete before the next begins. The pool enforces this by handing a
 * leased connection to exactly one caller at a time.
 *
 * Obtain one with [open]; release it with [close].
 */
internal class ClientConnection private constructor(
    private val channel: PipelinedChannel,
    private val bridge: SuspendMessageBridge<HttpResponse>,
    val route: RouteKey,
) {

    /**
     * True while the underlying channel is open. A pool checks this to skip
     * a connection the peer has closed while it sat idle.
     */
    val isActive: Boolean get() = channel.isActive

    /**
     * Writes [request], suspends until the complete response has been decoded,
     * and returns it fully materialised into GC-owned values plus whether the
     * connection may be reused. May be called repeatedly on the same connection
     * for keep-alive, but only serially.
     *
     * The write, the receive, the header materialisation, and the release of the
     * decoder's pooled headers all run on the channel's EventLoop dispatcher.
     * This is mandatory, not just tidy: the decoder borrows the response headers
     * from a **per-EventLoop-thread** pool ([io.github.fukusaka.keel.codec.http]
     * internal `HttpHeadersPool`), so releasing them on the caller's coroutine
     * thread — a different thread on a real multi-worker engine — corrupts that
     * thread-local pool (and the per-EventLoop buffer allocator). Materialising
     * here hands the caller only GC-owned state, so nothing pooled crosses a
     * thread boundary.
     */
    suspend fun exchange(request: HttpRequest): Exchanged = withContext(channel.ioDispatcher) {
        channel.pipeline.requestWriteAndFlush(request)
        val result = bridge.receiveCatching()
        val response = result.getOrNull()
            ?: throw (
                result.exceptionOrNull()
                    ?: IllegalStateException("connection closed before a complete response arrived")
                )
        // Materialise the pooled headers to GC-owned and release them here
        // (release-in-finally, on the EventLoop thread); isReusable reads the
        // pooled headers inside the block while they are still valid.
        response.materializeReleasingHeaders { detached ->
            Exchanged(KeelHttpResponse(response.status, detached, response.body ?: EMPTY_BODY), isReusable(response))
        }
    }

    /**
     * Tears the connection down: fires `inactive` on the pipeline (a
     * locally-initiated close delivers no peer FIN, so codec-held state would
     * otherwise never be released) then closes the channel. The two steps are
     * guarded independently so a throwing `notifyInactive` cannot skip the
     * close (fd leak).
     */
    suspend fun close() {
        try {
            withContext(NonCancellable + channel.ioDispatcher) {
                channel.pipeline.notifyInactive()
            }
        } finally {
            channel.close()
        }
    }

    companion object {
        /**
         * Opens a connection to [route] and installs the client codec
         * ([addHttp1ClientCodec]) plus a persistent response
         * [SuspendMessageBridge], ready for [exchange]. The bridge's
         * `releaseUndelivered` hook releases a response's pooled headers if
         * the connection is torn down while one is still buffered.
         *
         * @throws IllegalStateException if the engine does not return a
         *   [PipelinedChannel]. The just-opened channel is closed first so it
         *   does not leak.
         */
        suspend fun open(engine: StreamEngine, route: RouteKey): ClientConnection {
            val channel = engine.connect(route.host, route.port)
            var installed = false
            try {
                check(channel is PipelinedChannel) {
                    "KeelHttpClient requires a PipelinedChannel connection; " +
                        "got ${channel::class.simpleName} from ${engine::class.simpleName}"
                }
                val bridge = SuspendMessageBridge(
                    HttpResponse::class,
                    releaseUndelivered = { it.headers.release() },
                )
                withContext(channel.ioDispatcher) {
                    channel.addHttp1ClientCodec()
                    channel.pipeline.addLast("bridge", bridge)
                    channel.readEnabled = true
                }
                installed = true
                return ClientConnection(channel, bridge, route)
            } finally {
                if (!installed) closeQuietly(channel)
            }
        }

        private suspend fun closeQuietly(channel: Channel) {
            try {
                if (channel is PipelinedChannel) {
                    withContext(NonCancellable + channel.ioDispatcher) {
                        channel.pipeline.notifyInactive()
                    }
                }
            } finally {
                channel.close()
            }
        }

        /**
         * Whether the connection may be reused after [response]: it must be
         * keep-alive AND the response's body end must be unambiguous, so the
         * client knows where the next response begins.
         *
         * The end is determinate when the status is bodyless by definition
         * (204 / 304 — no body regardless of headers) or the response carries
         * explicit framing (`Content-Length` or `Transfer-Encoding: chunked`).
         * A response with none of these is delimited by connection close, so
         * its connection is already spent and must not be pooled.
         *
         * Reads [response]'s headers, so call it before releasing them. Pure —
         * takes no connection state — so a pool can decide reuse from the
         * response alone.
         */
        fun isReusable(response: HttpResponse): Boolean {
            if (!response.isKeepAlive) return false
            val bodyless = response.status == HttpStatus.NO_CONTENT ||
                response.status == HttpStatus.NOT_MODIFIED
            return bodyless ||
                response.headers.contentLength != null ||
                response.headers.isChunked
        }

        /** Shared empty body for bodyless responses. */
        private val EMPTY_BODY = ByteArray(0)
    }
}

/** The outcome of [ClientConnection.exchange]: the materialised response and whether the connection may be reused. */
internal class Exchanged(val response: KeelHttpResponse, val reusable: Boolean)
