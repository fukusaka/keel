package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.engine.nio.NioEngine
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the `KeelCio` factory — `embeddedServer(KeelCio)` driving
 * the keel transport stack via [NioEngine] but parsing requests with
 * `ktor-http-cio`.  Covers the basic request/response shapes that the bench
 * scenarios exercise (`/hello`, `/echo`, `/large`).
 */
class KeelCioEngineTest {

    @Test
    fun respondTextHello() {
        withKeelCioServer({ routing { get("/") { call.respondText("Hello") } } }) { port ->
            val (status, body) = httpGet(port, "/")
            assertEquals(200, status)
            assertEquals("Hello", body)
        }
    }

    @Test
    fun respondStatus404() {
        withKeelCioServer({ routing { get("/found") { call.respondText("OK") } } }) { port ->
            val (status, _) = httpGet(port, "/not-found")
            assertEquals(404, status)
        }
    }

    @Test
    fun postWithBody() {
        withKeelCioServer({
            routing {
                post("/echo") {
                    val body = call.receiveText()
                    call.respondText("echo:$body")
                }
            }
        }) { port ->
            val (status, body) = httpPost(port, "/echo", "hello-body")
            assertEquals(200, status)
            assertEquals("echo:hello-body", body)
        }
    }

    @Test
    fun largeResponse() {
        val largeText = "x".repeat(LARGE_PAYLOAD_BYTES)
        withKeelCioServer({ routing { get("/large") { call.respondText(largeText) } } }) { port ->
            val (status, body) = httpGet(port, "/large")
            assertEquals(200, status)
            assertEquals(LARGE_PAYLOAD_BYTES, body.length)
            assertTrue(body.all { it == 'x' })
        }
    }

    @Test
    fun respondWithCustomHeader() {
        withKeelCioServer({
            routing {
                get("/headers") {
                    call.response.headers.append("X-Custom", "keel-cio-value")
                    call.respondText("OK")
                }
            }
        }) { port ->
            val conn = openConnection(port, "/headers")
            assertEquals(200, conn.responseCode)
            assertEquals("keel-cio-value", conn.getHeaderField("X-Custom"))
            conn.disconnect()
        }
    }

    // --- Chunked streaming / SSE semantics ---

    /**
     * Verifies that [CioKeelStreamChannel] delivers frame 1 to the client
     * before the server produces frame 2.
     *
     * A [CompletableDeferred] gate synchronises the two sides: the server
     * suspends after flushing frame 1, and resumes only after the client has
     * read it.  This proves that the chunked body is not held in a buffer
     * waiting for subsequent frames — each [flush] results in observable
     * bytes at the client.
     */
    @Test
    fun `chunked streaming delivers frame 1 before server produces frame 2`() {
        val gate = CompletableDeferred<Unit>()
        withKeelCioServer({
            routing {
                get("/stream") {
                    call.respondBytesWriter {
                        writeStringUtf8("frame-1\n")
                        flush()
                        gate.await() // suspend until client has read frame 1
                        writeStringUtf8("frame-2\n")
                        flush()
                    }
                }
            }
        }) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = STREAM_TIMEOUT_MS
                socket.getOutputStream().let { out ->
                    out.write("GET /stream HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val headers = readHttpHeaders(reader)
                assertEquals(
                    "chunked",
                    headers["Transfer-Encoding"]?.lowercase(),
                    "response must use Transfer-Encoding: chunked",
                )

                // Frame 1 must arrive before we open the gate for frame 2.
                val chunk1 = readNextChunk(reader)
                assertEquals("frame-1\n", chunk1, "expected frame 1 content")

                gate.complete(Unit) // unblock server to produce frame 2

                val chunk2 = readNextChunk(reader)
                assertEquals("frame-2\n", chunk2, "expected frame 2 content")

                assertNull(readNextChunk(reader), "expected zero-length terminator chunk")
            }
        }
    }

    /**
     * Verifies that all N frames arrive with correct chunked wire encoding
     * when [CioKeelStreamChannel] is used for a streaming response.  Each
     * frame is sent with an individual [flush], and the final
     * `0\r\n\r\n` terminator must be present after the last data chunk.
     */
    @Test
    fun `chunked streaming all frames arrive with correct wire encoding`() {
        val frameCount = 10
        withKeelCioServer({
            routing {
                get("/sse") {
                    call.respondBytesWriter {
                        repeat(frameCount) { i ->
                            writeStringUtf8("data: event-$i\n\n")
                            flush()
                        }
                    }
                }
            }
        }) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = STREAM_TIMEOUT_MS
                socket.getOutputStream().let { out ->
                    out.write("GET /sse HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                readHttpHeaders(reader)

                var received = 0
                while (true) {
                    val chunk = readNextChunk(reader) ?: break
                    assertEquals("data: event-$received\n\n", chunk, "frame $received mismatch")
                    received++
                }
                assertEquals(frameCount, received, "expected $frameCount frames")
            }
        }
    }

    @Test
    fun keepAliveAcrossMultipleRequests() {
        withKeelCioServer({
            routing { get("/ping") { call.respondText("pong") } }
        }) { port ->
            // Two sequential requests on the same connection — the second
            // would fail if the keep-alive loop didn't reset cleanly.
            repeat(KEEPALIVE_ROUND_TRIPS) {
                val (status, body) = httpGet(port, "/ping")
                assertEquals(200, status)
                assertEquals("pong", body)
            }
        }
    }

    /**
     * Cancel-without-rethrow regression test for the ktor-cio path.
     *
     * **Scenario**: a handler calls `cancel(cause)` inside [io.ktor.server.response.respondBytesWriter]
     * without rethrowing. [CioKeelStreamChannel] (via [AbstractPipelinedWriteChannel.cancel])
     * completes `terminationDeferred` immediately without writing the chunked trailer
     * `0\r\n\r\n`. If the keep-alive loop then advances to the next request, the next
     * response's headers arrive at the client *before* the missing trailer — desynchronising
     * the client's HTTP parser.
     *
     * **Pre-fix (Red)**: [KeelCioApplicationResponse.writeChannelCancelled] was absent.
     * The keep-alive loop reached `if (!keepAlive) break` with `keepAlive=true`, read the
     * next request from the wire, and the sentinel handler ran (`sentinelInvoked=true`).
     *
     * **Post-fix (Green)**: `writeChannelCancelled` returns `true` → the loop breaks
     * before reading the next request → `sentinelInvoked` stays `false`.
     */
    @Test
    fun `cancel without rethrow closes keep-alive connection before next request`() {
        val sentinelInvoked = AtomicBoolean(false)

        withKeelCioServer({
            routing {
                get("/cancel-swallowed") {
                    call.respondBytesWriter {
                        writeFully("data".encodeToByteArray())
                        flush()
                        cancel(IOException("simulated client disconnect"))
                        // Intentionally NOT rethrowing.
                    }
                }
                get("/sentinel") {
                    sentinelInvoked.set(true)
                    call.respondText("sentinel")
                }
            }
        }, keepAlive = true) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3_000
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()

                val req1 = "GET /cancel-swallowed HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
                val req2 = "GET /sentinel HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                out.write(req1.toByteArray() + req2.toByteArray())
                out.flush()

                val buf = ByteArray(4096)
                try {
                    while (inp.read(buf) != -1) { /* drain */ }
                } catch (_: java.net.SocketTimeoutException) {
                    // Slow CI guard — assertion still catches the Red state because
                    // sentinelInvoked is set before any response write attempt.
                }
            }

            assertFalse(
                sentinelInvoked.get(),
                "cancel-without-rethrow (ktor-cio): /sentinel handler was invoked — writeChannelCancelled check " +
                    "is absent or not firing; the connection was not closed after cancel()-without-rethrow",
            )
        }
    }

    /**
     * Slow-reader high-water audit follow-up (ktor-cio variant): the chunked streaming path through
     * [CioKeelStreamChannel] (which inherits [io.github.fukusaka.keel.server.ktor.AbstractPipelinedWriteChannel])
     * must apply the high-water backpressure gate so a slow reader cannot drive
     * unbounded `pendingWrites` growth on the server.
     *
     * Mirrors `slow-reader high-water audit — flush suspends slow-reader producer beyond high-water mark`
     * in `KeelByteWriteChannelTest`, but exercised through the ktor-cio adapter
     * so the `:keel-server-ktor-cio` half of the audit is also Red-Green covered.
     *
     * Red-Green verification (manual): comment out the
     * `if (!pipelinedChannel.isWritable) { pipelinedChannel.awaitFlushComplete() }`
     * block in [io.github.fukusaka.keel.server.ktor.AbstractPipelinedWriteChannel.flush]
     * and run this test — it must fail (`iterationsCompleted` reaches `chunkCount`).
     * Restore the gate and the test passes.
     */
    @Test
    fun `slow-reader high-water audit — chunked streaming flush suspends slow-reader producer past high-water`() {
        val writerStarted = CompletableDeferred<Unit>()
        val iterationsCompleted = java.util.concurrent.atomic.AtomicInteger(0)
        val chunkSize = 16 * 1024
        val chunkCount = 500 // = 8 MB — overflows Linux loopback's auto-tuned rcvbuf max

        withKeelCioServer({
            routing {
                get("/slow-pump") {
                    writerStarted.complete(Unit)
                    call.respondBytesWriter {
                        repeat(chunkCount) { i ->
                            writeFully(ByteArray(chunkSize))
                            flush()
                            iterationsCompleted.set(i + 1)
                            // Give the EL time to run the dispatched emit task and
                            // update isWritable before the next flush observes it.
                            delay(SIMULATED_FRAME_GAP_MS)
                        }
                    }
                }
            }
        }, keepAlive = false) { port ->
            Socket().use { socket ->
                // Pin client SO_RCVBUF small to keep the test independent of platform
                // auto-tuning (Linux can otherwise grow rcvbuf into the multi-MB range
                // on loopback and absorb the full payload).
                socket.receiveBufferSize = SLOW_READER_RCVBUF
                socket.soTimeout = SOCKET_READ_TIMEOUT_MS
                socket.connect(java.net.InetSocketAddress("127.0.0.1", port), SOCKET_READ_TIMEOUT_MS)
                socket.getOutputStream().let { out ->
                    out.write("GET /slow-pump HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".toByteArray())
                    out.flush()
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                // Drain headers only; the body is intentionally left unread to
                // simulate a Slowloris-style slow consumer.
                while (reader.readLine()?.isNotEmpty() == true) {
                    /* drop header line */
                }

                runBlocking { withTimeout(5.seconds) { writerStarted.await() } }

                // With the gate the producer suspends after pendingBytes crosses
                // the high-water mark; without it, all chunkCount iterations
                // complete within ~chunkCount × SIMULATED_FRAME_GAP_MS.
                Thread.sleep(SLOW_READER_PAUSE_MS)

                val completed = iterationsCompleted.get()
                assertTrue(
                    completed < chunkCount,
                    "ktor-cio: producer completed all $chunkCount iterations during the " +
                        "slow-reader pause — chunked streaming high-water gate not engaging.",
                )

                // Drain the rest so the server-side close can settle cleanly.
                socket.getInputStream().readBytes()
            }
        }
    }

    private fun withKeelCioServer(
        module: suspend Application.() -> Unit,
        keepAlive: Boolean = true,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(KeelCio, port = 0, module = module)
        val cfg = server.engine.configuration
        cfg.engine = NioEngine()
        cfg.keepAlive = keepAlive
        server.start(wait = false)
        try {
            val port = runBlocking {
                withTimeout(15.seconds) {
                    server.engine.resolvedConnectors().first().port
                }
            }
            block(port)
        } finally {
            server.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
        }
    }

    private fun httpGet(port: Int, path: String): Pair<Int, String> {
        val conn = openConnection(port, path)
        val status = conn.responseCode
        val body = if (status in TWO_HUNDRED..TWO_NINETY_NINE) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText().orEmpty()
        }
        conn.disconnect()
        return status to body
    }

    private fun httpPost(port: Int, path: String, body: String): Pair<Int, String> {
        val conn = openConnection(port, path)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/plain")
        conn.setRequestProperty("Content-Length", body.length.toString())
        conn.outputStream.use { it.write(body.toByteArray()) }
        val status = conn.responseCode
        val responseBody = if (status in TWO_HUNDRED..TWO_NINETY_NINE) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText().orEmpty()
        }
        conn.disconnect()
        return status to responseBody
    }

    private fun openConnection(port: Int, path: String): HttpURLConnection {
        val url = URI("http://127.0.0.1:$port$path").toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
    }

    /**
     * Reads HTTP status line + headers from a raw socket [reader].
     * Returns a map of header names to values (first value wins on duplicates).
     * Leaves the reader positioned at the start of the response body.
     */
    private fun readHttpHeaders(reader: BufferedReader): Map<String, String> {
        reader.readLine() ?: error("EOF before status line")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                headers.putIfAbsent(name, value)
            }
        }
        return headers
    }

    /**
     * Reads one chunk from a `Transfer-Encoding: chunked` response body.
     *
     * Each chunk is preceded by its byte count in hexadecimal followed by
     * `\r\n`, then the data bytes, then a trailing `\r\n`.  Returns `null`
     * when the zero-length terminator chunk (`0\r\n\r\n`) is reached.
     */
    private fun readNextChunk(reader: BufferedReader): String? {
        val sizeLine = reader.readLine() ?: return null
        val size = sizeLine.trim().toInt(HEX_RADIX)
        if (size == 0) {
            reader.readLine() // consume trailing CRLF of terminator
            return null
        }
        val buf = CharArray(size)
        var pos = 0
        while (pos < size) {
            val n = reader.read(buf, pos, size - pos)
            if (n == -1) error("Unexpected EOF reading chunk data at offset $pos of $size")
            pos += n
        }
        reader.readLine() // consume trailing CRLF after chunk data
        return String(buf, 0, pos)
    }

    private companion object {
        private const val LARGE_PAYLOAD_BYTES = 100_000
        private const val KEEPALIVE_ROUND_TRIPS = 5
        private const val SHUTDOWN_GRACE_MS = 500L
        private const val SHUTDOWN_TIMEOUT_MS = 1000L
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
        private const val STREAM_TIMEOUT_MS = 5000
        private const val TWO_HUNDRED = 200
        private const val TWO_NINETY_NINE = 299
        private const val HEX_RADIX = 16

        /**
         * Pause the slow-reader test holds without consuming the body. With the
         * gate engaged the producer suspends well before this elapses; without
         * the gate it completes all chunks in ~chunkCount × frame-gap ms.
         */
        private const val SLOW_READER_PAUSE_MS = 3_000L

        /**
         * Per-frame gap that approximates real chunked / SSE producer pacing.
         * Gives the EL thread time to drain dispatched `emit` tasks and update
         * `isWritable` before the producer's next `flush()`.
         */
        private const val SIMULATED_FRAME_GAP_MS = 5L

        /** Read timeout for the slow-reader test's raw `Socket`. */
        private const val SOCKET_READ_TIMEOUT_MS = 5_000

        /**
         * Client-side `SO_RCVBUF` for the slow-reader test. Small value so the test
         * does not depend on platform receive-buffer auto-tuning (Linux loopback can
         * otherwise grow rcvbuf into the multi-MB range and absorb the full payload).
         */
        private const val SLOW_READER_RCVBUF = 16 * 1024
    }
}
