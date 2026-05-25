package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.server.http.dsl.KeelHttpServerBuilder

private val EMPTY_BODY = ByteArray(0)

/**
 * Installs the streaming benchmark routes used by `bench-stream-one.sh`
 * on a [KeelHttpServerBuilder]. Mirrors a subset of the pipeline-http
 * handler's route surface (`/upload-stream`, `/sse-stream`,
 * `/method-echo`, `/items/:id`) so a sweep can compare server-http
 * (`KeelHttpServer` DSL = v1.0 recommended API) against pipeline-http
 * (raw codec, floor) and the Ktor adapters on the same transport.
 *
 * Limited subset by intent — multipart parsing, compression-upload byte
 * accounting, etc. require more handler plumbing and are tracked as
 * follow-up issues. Adding more here keeps the KeelHttpServer DSL
 * contract as the single source of truth instead of duplicating the
 * pipeline-http hand-wired state machine.
 */
fun KeelHttpServerBuilder.installStreamingBenchRoutes() {
    get("/hello") { call -> call.respond(PipelineHttpResponses.hello) }
    get("/large") { call -> call.respond(PipelineHttpResponses.large) }

    // POST /upload-stream — discards request body, replies after body
    // end with X-Bytes-Received: <total>. Mirrors PipelineHttpRoutes
    // `/upload-stream`. Streams chunk-by-chunk (no aggregation) so RSS
    // stays flat across long uploads.
    post("/upload-stream") { call ->
        var total = 0L
        while (true) {
            val chunk = call.receiveChunk() ?: break
            total += chunk.readableBytes
            chunk.release()
        }
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Bytes-Received" to total.toString(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }

    // GET /sse-stream?count=N&size=M — emits N chunks of M bytes via
    // chunked transfer-encoding. Mirrors PipelineHttpRoutes
    // `/sse-stream`. A fresh IoBuf is allocated per chunk and ownership
    // transfers to the sink.
    get("/sse-stream") { call ->
        val count = (call.queryParameters["count"]?.toIntOrNull() ?: 10).coerceAtLeast(1)
        val size = (call.queryParameters["size"]?.toIntOrNull() ?: 1024).coerceAtLeast(1)
        val payload = ByteArray(size) { 0x20 }
        call.respondStream(
            HttpResponseHead(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_TYPE to "text/event-stream",
                    HttpHeaderName.TRANSFER_ENCODING to "chunked",
                    HttpHeaderName.CACHE_CONTROL to "no-cache",
                ),
            ),
        ) { sink ->
            val allocator = defaultAllocator()
            repeat(count) {
                val buf = allocator.allocate(size)
                buf.writeByteArray(payload, 0, size)
                sink.write(buf)
            }
        }
    }

    // /method-echo — echo of the request method name across the common
    // verbs used by the method-mix scenario.
    for (method in arrayOf(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE)) {
        route(method, "/method-echo") { call -> call.respondText(call.method.name) }
    }

    // GET /items/:id — path-parameter echo. Used by the path-param
    // scenario to surface the engine's pathParameters extraction cost.
    get("/items/:id") { call -> call.respondText(call.pathParameters["id"].orEmpty()) }
}
