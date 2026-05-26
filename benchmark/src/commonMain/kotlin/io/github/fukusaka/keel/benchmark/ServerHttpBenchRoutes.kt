package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseHead
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpVersion
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.server.http.dsl.KeelHttpServerBuilder
import io.github.fukusaka.keel.server.websocket.dsl.webSockets
import kotlinx.coroutines.delay

private val EMPTY_BODY = ByteArray(0)

/**
 * Installs the streaming benchmark routes used by `bench-stream-one.sh`
 * on a [KeelHttpServerBuilder]. Mirrors the pipeline-http handler's
 * route surface so a sweep can compare server-http (`KeelHttpServer`
 * DSL = v1.0 recommended API) against pipeline-http (raw codec, floor)
 * and the Ktor adapters on the same transport.
 *
 * Excluded by intent: response compression (`/large` with `Accept-Encoding`)
 * and request decompression (`/upload-stream` with `Content-Encoding: gzip`).
 * Both need `CompressionHandler` wired into the channel pipeline below the
 * codec, which today is a pipeline-level installer (not exposed by the
 * `KeelHttpServer` DSL). Tracked as follow-up; see status / plan notes.
 */
fun KeelHttpServerBuilder.installStreamingBenchRoutes() {
    get("/hello") { call -> call.respond(PipelineHttpResponses.hello) }
    get("/large") { call -> call.respond(PipelineHttpResponses.large) }

    // POST /upload-stream — discards request body chunk-by-chunk, replies
    // with `X-Bytes-Received: <total>`. Mirrors PipelineHttpRoutes.
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

    // POST /upload-slow — same as /upload-stream but inserts a small
    // suspend between chunks so wrk/k6's slow-upload scenario sees the
    // server cooperating with throttled clients. Mirrors PipelineHttpRoutes
    // `/upload-slow` (server-side delay, not client throttling).
    post("/upload-slow") { call ->
        var total = 0L
        while (true) {
            val chunk = call.receiveChunk() ?: break
            total += chunk.readableBytes
            chunk.release()
            delay(1)
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

    // POST /multipart-upload — streams body chunk-by-chunk, scans each
    // chunk for the benchmark's known boundary marker
    // ("--KeelBenchBoundaryV1") and reports the count via
    // `X-Parts-Received`. Mirrors PipelineHttpRoutes.
    post("/multipart-upload") { call ->
        var total = 0L
        var parts = 0
        var carry: ByteArray = EMPTY_BYTE_ARRAY
        while (true) {
            val chunk = call.receiveChunk() ?: break
            val len = chunk.readableBytes
            total += len
            val chunkBytes = ByteArray(len)
            chunk.readByteArray(chunkBytes, 0, len)
            chunk.release()
            val scan = scanMultipartBoundaries(
                carry = carry,
                chunk = chunkBytes,
                boundary = BENCHMARK_MULTIPART_BOUNDARY,
            )
            parts += scan.count
            carry = scan.carry
        }
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Bytes-Received" to total.toString(),
                    "X-Parts-Received" to parts.toString(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }

    // GET /sse-stream?count=N&size=M — emits N chunks of `data: <M
    // bytes>\n\n` via chunked transfer-encoding (default 100×1024 per
    // BENCHMARK_SSE_DEFAULT_*). Mirrors PipelineHttpRoutes — each chunk's
    // wire size is `data: ` (6) + payload (M) + `\n\n` (2) so the k6 SSE
    // scenario's body-length check passes.
    get("/sse-stream") { call ->
        val count = (call.queryParameters["count"]?.toIntOrNull() ?: BENCHMARK_SSE_DEFAULT_COUNT).coerceAtLeast(1)
        val size = (call.queryParameters["size"]?.toIntOrNull() ?: BENCHMARK_SSE_DEFAULT_SIZE).coerceAtLeast(1)
        val frameSize = 6 + size + 2
        val frame = ByteArray(frameSize).also {
            it[0] = 'd'.code.toByte(); it[1] = 'a'.code.toByte(); it[2] = 't'.code.toByte()
            it[3] = 'a'.code.toByte(); it[4] = ':'.code.toByte(); it[5] = ' '.code.toByte()
            for (i in 6 until 6 + size) it[i] = 'x'.code.toByte()
            it[6 + size] = '\n'.code.toByte(); it[7 + size] = '\n'.code.toByte()
        }
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
                val buf = allocator.allocate(frameSize)
                buf.writeByteArray(frame, 0, frameSize)
                sink.write(buf)
            }
        }
    }

    // /method-echo — multi-method echo. The k6 scenario verifies status
    // 200 + `X-Echo-Method: <UPPERCASE METHOD>` header, so the body is
    // empty and the response head carries the method via header.
    for (
        method in arrayOf(
            HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE,
            HttpMethod.PATCH, HttpMethod.OPTIONS, HttpMethod.HEAD,
        )
    ) {
        route(method, "/method-echo") { call ->
            call.respond(
                HttpResponse(
                    status = HttpStatus.OK,
                    version = HttpVersion.HTTP_1_1,
                    headers = HttpHeaders.of(
                        HttpHeaderName.CONTENT_LENGTH to "0",
                        "X-Echo-Method" to call.method.name,
                    ),
                    body = EMPTY_BODY,
                ),
            )
        }
    }

    // GET /items/:id — path-parameter echo via `X-Item-Id` header (the
    // k6 scenario reads the header, not the body) so the bench surfaces
    // the engine's pathParameters extraction cost.
    get("/items/:id") { call ->
        call.respond(
            HttpResponse(
                status = HttpStatus.OK,
                version = HttpVersion.HTTP_1_1,
                headers = HttpHeaders.of(
                    HttpHeaderName.CONTENT_LENGTH to "0",
                    "X-Item-Id" to call.pathParameters["id"].orEmpty(),
                ),
                body = EMPTY_BODY,
            ),
        )
    }
}

/**
 * Installs the WebSocket benchmark endpoints. Three routes (echo paths
 * for `bench-stream-one.sh`'s WS scenarios):
 *
 * - `/ws-echo` — plain echo (no compression). Used by `ws-echo`,
 *   `ws-large`, `ws-slow-consumer` scenarios (all connect to the same
 *   `/ws-echo` URL per the k6 scripts).
 * - `/ws-fragment` — plain echo (no compression). Used by the
 *   `ws-fragment` scenario (wsbench Go client).
 * - `/ws-deflate` — echo with `permessage-deflate` (DeflateCodec). Used
 *   by the `ws-deflate` scenario.
 *
 * Two `webSockets { … }` blocks are registered: one without compression
 * for `/ws-echo` + `/ws-fragment`, one with `DeflateCodec` for
 * `/ws-deflate`. Endpoints inside a `webSockets(codec)` group share
 * one upgrade pipeline so registering both routes together stays
 * cheap.
 */
fun KeelHttpServerBuilder.installWebSocketBenchRoutes() {
    webSockets {
        webSocket("/ws-echo") { for (m in incoming) send(m) }
        webSocket("/ws-fragment") { for (m in incoming) send(m) }
    }
    webSockets(DeflateCodec) {
        webSocket("/ws-deflate") { for (m in incoming) send(m) }
    }
}
