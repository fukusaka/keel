package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpBodyAggregator
import io.github.fukusaka.keel.codec.http.HttpRequest
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
 * HEAD ↔ [tls] ↔ encoder ↔ decoder ↔ aggregator ↔ routing ↔ TAIL
 * ```
 *
 * [HttpBodyAggregator] reassembles streaming body chunks into a
 * complete [HttpRequest]. The routing handler dispatches `/hello`,
 * `/large`, and `/echo` (POST body echo).
 */
fun installPipelineHttpHandlers(pipeline: ChannelPipeline) {
    pipeline.addLast("encoder", HttpResponseEncoder())
    pipeline.addLast("decoder", HttpRequestDecoder())
    pipeline.addLast("aggregator", HttpBodyAggregator())
    pipeline.addLast("routing", BenchmarkRoutingHandler)
}

/**
 * Terminal inbound handler for pipeline-http benchmarks.
 *
 * Routes:
 * - `/hello` — 13-byte "Hello, World!" (pre-built, zero per-request allocation)
 * - `/large` — 100 KB payload (pre-built)
 * - `/echo`  — echoes the request body (POST)
 * - others   — 404 Not Found
 */
private object BenchmarkRoutingHandler : ChannelInboundHandler {

    override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is HttpRequest) {
            ctx.propagateRead(msg)
            return
        }
        val response = when (msg.path) {
            "/hello" -> PipelineHttpResponses.hello
            "/large" -> PipelineHttpResponses.large
            "/echo" -> {
                val body = msg.body ?: ByteArray(0)
                HttpResponse.ok(body, contentType = "application/octet-stream")
            }
            else -> HttpResponse.notFound()
        }
        ctx.propagateWrite(response)
        ctx.propagateFlush()
    }
}
