package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
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
    private var echoBody: ByteArray? = null
    private var echoSize: Int = 0

    override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> {
                currentPath = msg.path
                if (msg.path == "/echo") {
                    echoBody = null
                    echoSize = 0
                }
            }
            is HttpBodyEnd -> {
                appendEchoBytes(msg)
                msg.content.release()
                emitResponse(ctx)
            }
            is HttpBody -> {
                appendEchoBytes(msg)
                msg.content.release()
            }
            else -> ctx.propagateRead(msg)
        }
    }

    private fun appendEchoBytes(body: HttpBody) {
        if (currentPath != "/echo") return
        val len = body.content.readableBytes
        if (len == 0) return
        val cur = echoBody
        val needed = echoSize + len
        val buf = when {
            cur == null -> ByteArray(maxOf(len, INITIAL_ECHO_CAPACITY))
            cur.size < needed -> cur.copyOf(maxOf(needed, cur.size * 2))
            else -> cur
        }
        echoBody = buf
        body.content.readByteArray(buf, echoSize, len)
        echoSize += len
    }

    private fun emitResponse(ctx: ChannelHandlerContext) {
        val response = when (currentPath) {
            "/hello" -> PipelineHttpResponses.hello
            "/large" -> PipelineHttpResponses.large
            "/echo" -> {
                val body = if (echoSize > 0) echoBody!!.copyOf(echoSize) else ByteArray(0)
                echoBody = null
                echoSize = 0
                HttpResponse.ok(body, contentType = "application/octet-stream")
            }
            else -> HttpResponse.notFound()
        }
        currentPath = null
        ctx.propagateWrite(response)
        ctx.propagateFlush()
    }

    private companion object {
        private const val INITIAL_ECHO_CAPACITY = 256
    }
}
