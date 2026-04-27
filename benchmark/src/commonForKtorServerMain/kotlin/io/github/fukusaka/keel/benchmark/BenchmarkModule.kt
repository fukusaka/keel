package io.github.fukusaka.keel.benchmark

import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.post
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
 * - `GET /sse-stream?count=N&size=M` — emits N chunks of M bytes via
 *   chunked Transfer-Encoding (used to measure server-to-client streaming
 *   throughput on the engine's response-body write path)
 *
 * @param connectionClose if true, add `Connection: close` header to force
 *   per-request TCP connections (used by keel-equiv profile)
 */
fun Application.benchmarkModule(connectionClose: Boolean = false) {
    if (connectionClose) {
        intercept(ApplicationCallPipeline.Plugins) {
            call.response.headers.append("Connection", "close")
        }
    }
    // WebSockets plugin install: required for `webSocket("/ws-echo") { ... }`.
    // Engines that support `respondUpgrade` (Ktor CIO, Ktor Netty, Pattern B
    // after Step 1, Pattern C after Step 3) handle the upgrade. Engines that
    // throw `UnsupportedOperationException` from `respondUpgrade` (current
    // Pattern B) reject the upgrade at handshake time — k6 ws-echo scenario
    // reports those as connection errors and the benchmark just shows zero
    // throughput for that engine, which is the expected behaviour pre-Step 1.
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
            // the body (current Pattern B) hold the entire payload in memory;
            // engines that stream (Pattern C, future Pattern B refactor) drain
            // chunks as they arrive. Heap-impact differences show up in JFR /
            // GC logs collected during the bench run.
            val received = call.receiveChannel().discard()
            call.response.headers.append("X-Bytes-Received", received.toString())
            call.respondBytes(uploadAckBytes, ContentType.Text.Plain)
        }
        get("/sse-stream") {
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
