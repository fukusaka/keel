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
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline

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
fun installPipelineHttpHandlers(pipeline: Pipeline) {
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
 * - `/upload-stream` — discards request body chunks, replies 200 + size header
 *   (request-body streaming throughput / heap-impact bench)
 * - `/sse-stream?count=N&size=M` — emits N chunks of M bytes via chunked
 *   Transfer-Encoding (response-body streaming throughput bench)
 * - others   — 404 Not Found
 *
 * Instantiated per-connection because [currentPath] / [echoStreaming] /
 * [uploadBytes] are mutable state scoped to the current request.
 */
private class BenchmarkRoutingHandler : InboundHandler {

    private var currentPath: String? = null
    private var echoStreaming: Boolean = false
    private var uploadStreaming: Boolean = false
    private var uploadBytes: Long = 0L

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> {
                currentPath = msg.path
                echoStreaming = false
                uploadStreaming = false
                uploadBytes = 0L
                when {
                    msg.path == "/echo" -> {
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
                    msg.path == "/upload-stream" -> {
                        // Streaming upload: count incoming bytes per chunk, reply
                        // after HttpBodyEnd. No memory aggregation — chunks are
                        // released as they arrive.
                        uploadStreaming = true
                    }
                    msg.path?.startsWith("/sse-stream") == true -> {
                        emitSseStream(ctx, msg)
                    }
                }
            }
            is HttpBodyEnd -> {
                when {
                    echoStreaming -> {
                        if (msg.content.readableBytes > 0) {
                            msg.content.retain()
                            ctx.propagateWrite(HttpBody(msg.content))
                        }
                        ctx.propagateWrite(HttpBodyEnd.EMPTY)
                        ctx.propagateFlush()
                        echoStreaming = false
                    }
                    uploadStreaming -> {
                        uploadBytes += msg.content.readableBytes
                        emitUploadAck(ctx)
                        uploadStreaming = false
                    }
                    else -> emitResponse(ctx)
                }
                msg.content.release()
            }
            is HttpBody -> {
                when {
                    echoStreaming -> {
                        // Zero-copy: pass the body chunk IoBuf directly to the
                        // response encoder. The IoBuf is a platform-native slice
                        // (NativeIoBuf/DirectIoBuf) from allocator.slice(), so
                        // it is transport-compatible.
                        msg.content.retain()
                        ctx.propagateWrite(HttpBody(msg.content))
                    }
                    uploadStreaming -> {
                        uploadBytes += msg.content.readableBytes
                    }
                }
                msg.content.release()
            }
            else -> ctx.propagateRead(msg)
        }
    }

    private fun emitResponse(ctx: PipelineHandlerContext) {
        val response = when (currentPath) {
            "/hello" -> PipelineHttpResponses.hello
            "/large" -> PipelineHttpResponses.large
            else -> HttpResponse.notFound()
        }
        currentPath = null
        ctx.propagateWrite(response)
        ctx.propagateFlush()
    }

    private fun emitUploadAck(ctx: PipelineHandlerContext) {
        ctx.propagateWrite(
            HttpResponse.ok("ok", contentType = "text/plain").apply {
                headers.add("X-Bytes-Received", uploadBytes.toString())
            },
        )
        ctx.propagateFlush()
        currentPath = null
    }

    private fun emitSseStream(ctx: PipelineHandlerContext, head: HttpRequestHead) {
        val params = head.queryString.orEmpty()
        val count = parseQueryInt(params, "count") ?: SSE_DEFAULT_COUNT
        val size = parseQueryInt(params, "size") ?: SSE_DEFAULT_SIZE
        ctx.propagateWrite(
            HttpResponseHead(
                status = HttpStatus.OK,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_TYPE to "text/event-stream",
                    HttpHeaderName.TRANSFER_ENCODING to "chunked",
                ),
            ),
        )
        val payload = "data: ${"x".repeat(size)}\n\n".encodeToByteArray()
        repeat(count) {
            // Allocate one IoBuf per frame; the allocator is per-EventLoop and
            // recycles immediately after the encoder has serialised + flushed.
            val buf = ctx.channel.allocator.allocate(payload.size)
            buf.writeByteArray(payload, 0, payload.size)
            ctx.propagateWrite(HttpBody(buf))
        }
        ctx.propagateWrite(HttpBodyEnd.EMPTY)
        ctx.propagateFlush()
        currentPath = null
    }

    private fun parseQueryInt(query: String, name: String): Int? {
        if (query.isEmpty()) return null
        for (pair in query.splitToSequence('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == name) {
                return pair.substring(eq + 1).toIntOrNull()
            }
        }
        return null
    }

    private companion object {
        /** Default SSE frame count for `/sse-stream` (override via `?count=N`). */
        const val SSE_DEFAULT_COUNT = 100

        /** Default SSE frame payload size in bytes for `/sse-stream` (override via `?size=M`). */
        const val SSE_DEFAULT_SIZE = 1024
    }
}
