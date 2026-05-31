package io.github.fukusaka.keel.benchmark

import io.ktor.http.ContentType
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.receiveChannel
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.options
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.discard
import io.ktor.utils.io.writeFully
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText

/**
 * Shared Ktor routing module for benchmark servers.
 *
 * Provides identical endpoints across all engine configurations
 * so throughput differences reflect only engine overhead.
 *
 * Endpoints:
 * - `GET /hello` — 13-byte "Hello, World!" (HTTP/1.1 baseline RPS)
 * - `GET /large` — 100 KB text payload (write-side throughput)
 * - `POST /upload-stream` — discards request body, replies 200 + size header
 *   (used to measure streaming-upload throughput on the engine's request-body
 *   read path)
 * - `POST /multipart-upload` — parses the incoming `multipart/form-data`,
 *   counts parts + total payload bytes, replies 200 with both as response
 *   headers. Exposes the framework's multipart-parser cost on top of the
 *   raw drain that `/upload-stream` measures.
 * - `GET /sse-stream?count=N&size=M` — emits N chunks of M bytes via
 *   chunked Transfer-Encoding (used to measure server-to-client streaming
 *   throughput on the engine's response-body write path)
 *
 * @param connectionClose if true, add `Connection: close` header to force
 *   per-request TCP connections (used by keel-equiv profile)
 * @param compression if true, install Ktor's [Compression] plugin so
 *   responses are compressed when the client sends `Accept-Encoding`
 *   (gzip / deflate). Off by default — preserves the existing
 *   uncompressed `/hello` and `/large` baselines for non-compression
 *   scenarios. The `compression.js` k6 scenario opts in via
 *   `BENCH_COMPRESSION_ENABLE=true` (forwarded as `--compression=true`
 *   by `bench-stream-one.sh`).
 */
fun Application.benchmarkModule(connectionClose: Boolean = false, compression: Boolean = false) {
    if (connectionClose) {
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.headers.append("Connection", "close")
        }
    }
    installBenchmarkCompression(compression)
    // WebSockets plugin install: required for `webSocket("/ws-echo") { ... }`.
    // Engines that support `respondUpgrade` (Ktor CIO, Ktor Netty, the
    // `:keel-server-ktor-cio` adapter) handle the upgrade. Engines that
    // throw `UnsupportedOperationException` from `respondUpgrade` (the
    // current `:keel-server-ktor` adapter) reject the upgrade at handshake
    // time — k6 ws-echo scenario reports those as connection errors and the
    // benchmark just shows zero throughput for that engine, which is the
    // expected behaviour until `respondUpgrade` lands in `:keel-server-ktor`.
    install(WebSockets)
    routing {
        get("/hello") {
            call.respondBytes(helloPayloadBytes, ContentType.Text.Plain)
        }
        get("/large") {
            call.respondBytes(largePayloadBytes, ContentType.Text.Plain)
        }
        post("/upload-stream") {
            // Streaming upload: discard incoming body chunks via the engine's
            // read channel, reply with the byte count. Engines that aggregate
            // the body in memory hold the entire payload before the handler
            // runs; engines that stream the body chunks (`:keel-server-ktor-cio`
            // today, future refactor of `:keel-server-ktor`) drain chunks as
            // they arrive. Heap-impact differences show up in JFR / GC logs
            // collected during the bench run.
            val received = call.receiveChannel().discard()
            call.response.headers.append("X-Bytes-Received", received.toString())
            call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
        }
        post("/multipart-upload") {
            // Multipart upload: framework parses parts, counts bytes per part.
            // The bench script (k6/multipart.js) sends a fixed-shape payload
            // (PARTS parts of PART_BYTES bytes each) so the per-iteration
            // parse cost is the only thing engines differ on.
            var partCount = 0
            var totalBytes = 0L
            val parts = call.receiveMultipart()
            parts.forEachPart { part ->
                if (part is PartData.FileItem) {
                    // Drain the file part's channel via the engine's read
                    // channel directly. `discard()` returns the byte count
                    // without ever materialising a ByteArray, so the bench
                    // measures the parser's drain throughput rather than
                    // allocator pressure.
                    totalBytes += part.provider().discard()
                }
                partCount++
                part.dispose()
            }
            call.response.headers.append("X-Parts-Received", partCount.toString())
            call.response.headers.append("X-Bytes-Received", totalBytes.toString())
            call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
        }
        // Method-mix endpoint: any of GET/POST/PUT/DELETE/PATCH/HEAD on
        // /method-echo replies 200 with `X-Echo-Method` echoing the request
        // method. Used by `bench-stream-one.sh method-mix` to surface the
        // engine's per-method routing dispatch cost.
        route("/method-echo") {
            // Inline the response so each method block has its own
            // `RoutingContext` receiver — Ktor's per-method DSL does not
            // hand back a shared handler the way some routers do.
            get {
                call.response.headers.append("X-Echo-Method", call.request.httpMethod.value)
                call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
            }
            post {
                call.response.headers.append("X-Echo-Method", call.request.httpMethod.value)
                call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
            }
            put {
                call.response.headers.append("X-Echo-Method", call.request.httpMethod.value)
                call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
            }
            delete {
                call.response.headers.append("X-Echo-Method", call.request.httpMethod.value)
                call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
            }
            patch {
                call.response.headers.append("X-Echo-Method", call.request.httpMethod.value)
                call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
            }
            head {
                // HEAD: no body, but Ktor still sets headers + status from
                // respondBytes (it strips the body itself).
                call.response.headers.append("X-Echo-Method", call.request.httpMethod.value)
                call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
            }
            options {
                call.response.headers.append("X-Echo-Method", call.request.httpMethod.value)
                call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
            }
        }
        // Path-parameter endpoint: GET /items/{id} replies 200 with
        // `X-Item-Id` echoing the parsed id. Used by
        // `bench-stream-one.sh path-param` to surface the engine's
        // path-parameter routing dispatch cost.
        get("/items/{id}") {
            val id = call.parameters["id"] ?: ""
            call.response.headers.append("X-Item-Id", id)
            call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
        }
        get("/sse-stream") {
            // SSE per-frame contract for the bench harness:
            //   * The handler emits exactly `count` frames of
            //     `"data: " + size×'x' + "\n\n"` bytes (k6 sse.js verifies
            //     the total body length as `count * (6 + size + 2)`).
            //   * `flush()` after each frame is required for the bench to
            //     measure per-event throughput, not bulk delivery.
            //
            // Whether `flush()` actually reaches the wire as a per-frame
            // `requestFlush` depends on the engine adapter:
            //   * `ktor-keel-*` and `ktor-cio-keel-*` (PR #441) now
            //     own a `BufferedByteWriteChannel` impl that maps each user
            //     `flush()` to one `requestWrite + requestFlush` on the
            //     pipelined channel.
            //   * `ktor-cio` (the upstream Ktor engine) honours `flush()`
            //     directly through its own ByteWriteChannel.
            // History: prior to PR #441 the keel-server-ktor adapter
            // bridged Ktor's `ByteChannel` through a `readAvailable(8 KB)`
            // worker, coalescing multiple per-user flushes into one engine
            // flush. The PR #441 fix removed that bridge.
            val count = call.request.queryParameters["count"]?.toIntOrNull() ?: BENCHMARK_SSE_DEFAULT_COUNT
            val size = call.request.queryParameters["size"]?.toIntOrNull() ?: BENCHMARK_SSE_DEFAULT_SIZE
            val payload = sseFramePayload(size)
            call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
                repeat(count) {
                    writeFully(payload)
                    flush()
                }
            }
        }
        webSocket("/ws-echo") {
            // Echo every binary / text frame the client sends back to it. k6's
            // ws-echo scenario opens a connection, sends a fixed-size message,
            // waits for the echo, and counts round-trips per second.
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> send(Frame.Text(frame.readText()))
                    is Frame.Binary -> send(Frame.Binary(true, frame.readBytes()))
                    else -> Unit
                }
            }
        }
    }
}

/** 100KB text payload, pre-allocated to avoid allocation during benchmarks. */
private val largePayload = "x".repeat(LARGE_PAYLOAD_SIZE)

/** Pre-encoded payloads to measure pure I/O performance without per-request String.encodeToByteArray(). */
private val helloPayloadBytes = "Hello, World!".encodeToByteArray()
private val largePayloadBytes = largePayload.encodeToByteArray()

/** Reply body for `/upload-stream` — short ack, the meaningful work is the inbound discard. */
private val uploadAckBytes = "ok".encodeToByteArray()

/**
 * Builds an SSE frame with `M` bytes of payload, prefixed by `data: ` and
 * terminated by the SSE blank-line delimiter.
 *
 * Sized to match the user's `?size=` parameter so a single bench run can
 * sweep across small (telemetry-shape) vs. large (chat-shape) frames.
 */
private fun sseFramePayload(sizeBytes: Int): ByteArray {
    val body = "x".repeat(sizeBytes)
    return "data: $body\n\n".encodeToByteArray()
}
