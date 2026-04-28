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
 * - `/multipart-upload` — streams the request body, scans each chunk for the
 *   bench scenario's known boundary marker (`--KeelBenchBoundaryV1`), counts
 *   the parts, replies 200 + count + size headers. No framework multipart
 *   parser; the boundary scan is the cheapest possible "parse" so the
 *   bench number is the wire-side baseline against which framework parsers
 *   (Spring / Vertx / Netty / Ktor) can be compared in `bench-stream-one.sh
 *   multipart`.
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
    private var multipartStreaming: Boolean = false
    private var multipartParts: Int = 0
    // 1-byte carry buffer for boundary scanning across chunk boundaries.
    // Boundary length is constant (BENCHMARK_MULTIPART_BOUNDARY.size) and
    // small (<60 bytes), so we keep the last (boundaryLen - 1) bytes from
    // the previous chunk to catch boundary occurrences that span chunks.
    private var multipartCarry: ByteArray = EMPTY_BYTE_ARRAY
    private var methodEchoMethod: String? = null
    private var itemEchoId: String? = null

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> {
                currentPath = msg.path
                echoStreaming = false
                uploadStreaming = false
                uploadBytes = 0L
                methodEchoMethod = null
                itemEchoId = null
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
                    msg.path == "/multipart-upload" -> {
                        // Streaming multipart: count bytes (same accounting as
                        // upload-stream) AND scan each chunk for the bench
                        // scenario's known boundary marker so X-Parts-Received
                        // is real. The boundary scan is the cheapest possible
                        // "parse" — it has no header parse and no per-part
                        // allocation, so the bench number is the wire-side
                        // baseline that framework parsers can be compared to.
                        multipartStreaming = true
                        multipartParts = 0
                        multipartCarry = EMPTY_BYTE_ARRAY
                    }
                    msg.path == "/method-echo" -> {
                        // Echo on BodyEnd (not here) so /method-echo + /items/{id}
                        // share the existing emitResponse path with /hello + /large.
                        // Stash the method on the handler so emitResponse can read it.
                        methodEchoMethod = msg.method.name
                    }
                    msg.path?.startsWith("/items/") == true -> {
                        // Same pattern as /method-echo: stash the parsed id and let
                        // emitResponse run on HttpBodyEnd.
                        itemEchoId = msg.path!!.substring("/items/".length)
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
                    multipartStreaming -> {
                        if (msg.content.readableBytes > 0) {
                            uploadBytes += msg.content.readableBytes
                            scanMultipartChunk(msg.content)
                        }
                        emitMultipartAck(ctx)
                        multipartStreaming = false
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
                    multipartStreaming -> {
                        uploadBytes += msg.content.readableBytes
                        scanMultipartChunk(msg.content)
                    }
                }
                msg.content.release()
            }
            else -> ctx.propagateRead(msg)
        }
    }

    private fun emitResponse(ctx: PipelineHandlerContext) {
        val response = when {
            currentPath == "/hello" -> PipelineHttpResponses.hello
            currentPath == "/large" -> PipelineHttpResponses.large
            methodEchoMethod != null -> HttpResponse.ok("ok", contentType = "text/plain").apply {
                headers.add("X-Echo-Method", methodEchoMethod!!)
            }
            itemEchoId != null -> HttpResponse.ok("ok", contentType = "text/plain").apply {
                headers.add("X-Item-Id", itemEchoId!!)
            }
            else -> HttpResponse.notFound()
        }
        currentPath = null
        methodEchoMethod = null
        itemEchoId = null
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

    private fun emitMultipartAck(ctx: PipelineHandlerContext) {
        // The k6 body has (parts + 1) boundary marker occurrences: one
        // before each part plus the trailing `--boundary--`. Subtract 1
        // so X-Parts-Received matches the client's PARTS env. Clamp to
        // 0 in case the body was empty or malformed (defensive).
        val reportedParts = (multipartParts - 1).coerceAtLeast(0)
        ctx.propagateWrite(
            HttpResponse.ok("ok", contentType = "text/plain").apply {
                headers.add("X-Parts-Received", reportedParts.toString())
                headers.add("X-Bytes-Received", uploadBytes.toString())
            },
        )
        ctx.propagateFlush()
        currentPath = null
    }

    /**
     * Counts occurrences of the bench scenario's known boundary marker
     * across this chunk plus any carry from the previous chunk. The k6
     * `multipart.js` builds the body with `--KeelBenchBoundaryV1` as the
     * boundary; counting opening boundaries (one per part) plus the
     * trailing closing boundary `--KeelBenchBoundaryV1--` matches the
     * `parts + 1` total. We subtract 1 in [emitMultipartAck] (well, here
     * before reporting) so `X-Parts-Received` matches the client's
     * `PARTS` env.
     *
     * The carry buffer holds the last `(boundary.size - 1)` bytes from
     * the previous chunk so a boundary that straddles a chunk boundary
     * is still detected. For the bench's typical chunk sizes (single-
     * chunk multipart bodies of a few KB) the carry rarely matters but
     * keeps the scanner correct under arbitrary chunking.
     */
    private fun scanMultipartChunk(content: io.github.fukusaka.keel.buf.IoBuf) {
        val n = content.readableBytes
        if (n == 0) return
        // Copy IoBuf bytes into a ByteArray, then delegate to the pure
        // helper (testable in isolation, see BenchmarkRouteSupportTest).
        val chunk = ByteArray(n)
        for (i in 0 until n) {
            chunk[i] = content.getByte(content.readerIndex + i)
        }
        val result = scanMultipartBoundaries(
            carry = multipartCarry,
            chunk = chunk,
            boundary = BENCHMARK_MULTIPART_BOUNDARY,
        )
        multipartParts += result.count
        multipartCarry = result.carry
    }

    private fun emitSseStream(ctx: PipelineHandlerContext, head: HttpRequestHead) {
        val params = head.queryString.orEmpty()
        val count = parseBenchmarkQueryInt(params, "count") ?: BENCHMARK_SSE_DEFAULT_COUNT
        val size = parseBenchmarkQueryInt(params, "size") ?: BENCHMARK_SSE_DEFAULT_SIZE
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

}
