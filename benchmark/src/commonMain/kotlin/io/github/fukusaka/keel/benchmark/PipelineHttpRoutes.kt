package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelInboundHandler
import io.github.fukusaka.keel.pipeline.ChannelPipeline

/**
 * Pre-built responses shared across all pipeline-http benchmark servers.
 *
 * Header flat-array caches are warmed at construction time so that the
 * first request on each EventLoop thread does not pay the lazy-init cost.
 */
object PipelineHttpResponses {
    val hello: HttpResponse = HttpResponse.ok("Hello, World!", contentType = "text/plain").also { it.headers.size }
    val large: HttpResponse = HttpResponse.ok("x".repeat(LARGE_PAYLOAD_SIZE), contentType = "text/plain").also { it.headers.size }
}

/**
 * Installs the standard pipeline-http handler stack into [pipeline]:
 *
 * ```
 * HEAD ↔ [tls] ↔ encoder ↔ decoder ↔ routing ↔ TAIL
 * ```
 *
 * No [HttpBodyAggregator] — the routing handler consumes streaming
 * body messages ([HttpBody] / [HttpBodyEnd]) directly. This avoids
 * the aggregator's per-request body copy overhead in benchmarks.
 */
fun installPipelineHttpHandlers(pipeline: ChannelPipeline) {
    pipeline.addLast("encoder", HttpResponseEncoder())
    pipeline.addLast("decoder", HttpRequestDecoder())
    pipeline.addLast("routing", BenchmarkRoutingHandler())
}

/**
 * Terminal inbound handler for pipeline-http benchmarks.
 *
 * Handles the streaming HTTP message protocol directly:
 * [HttpRequestHead] → [HttpBody] × N → [HttpBodyEnd].
 *
 * Routes:
 * - `/hello` — 13-byte "Hello, World!" (pre-built, zero per-request allocation)
 * - `/large` — 100 KB payload (pre-built)
 * - `/echo`  — accumulates request body chunks and echoes them back
 * - others   — 404 Not Found
 *
 * Instantiated per-connection because [echoBody] / [echoSize] are
 * mutable state scoped to the current request.
 */
private class BenchmarkRoutingHandler : ChannelInboundHandler {

    private var currentPath: String? = null
    private var echoStreaming: Boolean = false

    override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> {
                currentPath = msg.path
                echoStreaming = false
                if (msg.path == "/echo") {
                    // Start streaming response immediately with chunked encoding.
                    // Body chunks from the request will be forwarded as-is to the
                    // response encoder (zero-copy echo).
                    ctx.propagateWrite(
                        HttpResponseHead(
                            status = HttpStatus.OK,
                            headers = HttpHeaders.of(
                                HttpHeaderName.CONTENT_TYPE to "application/octet-stream",
                                HttpHeaderName.TRANSFER_ENCODING to "chunked",
                            ),
                        ),
                    )
                    echoStreaming = true
                }
            }
            is HttpBodyEnd -> {
                if (echoStreaming) {
                    if (msg.content.readableBytes > 0) {
                        msg.content.retain()
                        ctx.propagateWrite(HttpBody(msg.content))
                    }
                    ctx.propagateWrite(HttpBodyEnd.EMPTY)
                    ctx.propagateFlush()
                    echoStreaming = false
                } else {
                    emitResponse(ctx)
                }
                msg.content.release()
            }
            is HttpBody -> {
                if (echoStreaming) {
                    // Zero-copy: pass the body chunk IoBuf directly to the
                    // response encoder. The IoBuf is a platform-native slice
                    // (NativeIoBuf/DirectIoBuf) from allocator.slice(), so
                    // it is transport-compatible.
                    msg.content.retain()
                    ctx.propagateWrite(HttpBody(msg.content))
                }
                msg.content.release()
            }
            else -> ctx.propagateRead(msg)
        }
    }

    private fun emitResponse(ctx: ChannelHandlerContext) {
        val response = when (currentPath) {
            "/hello" -> PipelineHttpResponses.hello
            "/large" -> PipelineHttpResponses.large
            else -> HttpResponse.notFound()
        }
        currentPath = null
        ctx.propagateWrite(response)
        ctx.propagateFlush()
    }
}
