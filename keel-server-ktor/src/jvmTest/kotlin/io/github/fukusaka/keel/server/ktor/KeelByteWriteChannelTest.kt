package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.engine.netty.NettyEngine
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.utils.io.writeStringUtf8
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Integration tests for [KeelByteWriteChannel]'s close-cause wrap policy:
 *
 * - `wrapClosedCause` policy mirrors Ktor's `CloseToken.wrapCause`,
 *   wrapping arbitrary throwables as [ClosedWriteChannelException] with
 *   the original as `cause`.
 * - `closedCause` getter returns a fresh wrapper instance per access so
 *   identity comparisons / `addSuppressed` accumulation cannot
 *   cross-contaminate.
 *
 * The pre-fix code was `if (cause != null) throw cause`, which would
 * throw the raw `IllegalStateException` directly. The post-fix code goes
 * through `wrapClosedCause(...)` and throws a fresh
 * `ClosedWriteChannelException(cause)`. Pre-fix, [`flush after cancel
 * throws ClosedWriteChannelException wrapping the user cause`] fails
 * (raw cause type leaks through); post-fix it passes — Red-Green
 * regression check for the wrap policy.
 */
class KeelByteWriteChannelTest {

    @Test
    fun `flush after cancel throws ClosedWriteChannelException wrapping the user cause`() {
        val cause = IllegalStateException("simulated abort")
        val observed = AtomicReference<Throwable?>(null)

        withKeelServer({
            routing {
                get("/cancel-then-flush") {
                    call.respondBytesWriter {
                        writeFully("first".encodeToByteArray())
                        flush()
                        cancel(cause)
                        try {
                            // Pre-fix this would throw `cause` (IllegalStateException).
                            // Post-fix it throws ClosedWriteChannelException(cause).
                            flush()
                            fail("expected flush() to throw after cancel")
                        } catch (e: Throwable) {
                            observed.set(e)
                            // Re-throw so the response cycle terminates cleanly.
                            throw e
                        }
                    }
                }
            }
        }) { port ->
            // Trigger the route and consume what we can — body may be partial.
            runCatching { httpGet(port, "/cancel-then-flush") }
        }

        val exception = observed.get()
        assertNotNull(exception, "expected flush() to throw after cancel; nothing observed")
        assertTrue(
            exception is ClosedWriteChannelException,
            "expected ClosedWriteChannelException, got ${exception::class.simpleName}: $exception",
        )
        assertSame(cause, exception.cause, "wrapper should preserve the user-supplied cause")
    }

    @Test
    fun `closedCause yields a fresh wrapper per access`() {
        val cause = IllegalStateException("frozen channel")
        val first = AtomicReference<Throwable?>(null)
        val second = AtomicReference<Throwable?>(null)

        withKeelServer({
            routing {
                get("/closed-cause-identity") {
                    call.respondBytesWriter {
                        writeFully("hello".encodeToByteArray())
                        flush()
                        cancel(cause)
                        // The contract: every read of `closedCause`
                        // returns a fresh wrapper. Throwing the same
                        // Throwable instance repeatedly mutates its
                        // stack trace and lets `addSuppressed`
                        // accumulate, so Ktor's CloseToken creates a
                        // new wrapper each time `closedCause` is read
                        // (CloseToken.wrapCause). Mirror it here.
                        first.set(closedCause)
                        second.set(closedCause)
                        // Stop the writer cleanly so the response pipeline
                        // can finalise the connection.
                        runCatching { flush() }
                    }
                }
            }
        }) { port ->
            runCatching { httpGet(port, "/closed-cause-identity") }
        }

        val a = first.get()
        val b = second.get()
        assertNotNull(a)
        assertNotNull(b)
        assertNotSame(a, b, "closedCause should return a fresh wrapper per access")
        assertSame(cause, a.cause)
        assertSame(cause, b.cause)
        assertTrue(a is ClosedWriteChannelException)
        assertTrue(b is ClosedWriteChannelException)
    }

    /**
     * K38b regression test: [KeelByteWriteChannel.cancel] without a re-throw must not leave
     * the keep-alive loop running with the encoder still in `CHUNKED` mode.
     *
     * **Scenario**: a handler calls `cancel(cause)` inside [io.ktor.server.response.respondBytesWriter]
     * without re-throwing, so [engine.pipeline.execute] returns normally.
     * [AbstractPipelinedWriteChannel.cancel] completes [terminationDeferred] immediately without
     * writing [io.github.fukusaka.keel.codec.http.HttpBodyEnd], leaving the
     * [io.github.fukusaka.keel.codec.http.HttpResponseEncoder] in `CHUNKED` mode.
     *
     * **Pre-fix (Red)**: [KeelApplicationResponse.writeChannelCancelled] property was absent.
     * [processRequest][KeelCodecConnectionHandler] returned `keepAlive = true`, the loop read
     * the next request (incrementing [sentinelInvoked]), and then the encoder's
     * `check(streamingMode == NONE)` threw — connection closed with "Connection handling failed".
     *
     * **Post-fix (Green)**: `writeChannelCancelled` returns `true` → `processRequest` returns
     * `false` → loop exits before ever reading the second request → [sentinelInvoked] stays `false`.
     *
     * Red-Green verification: run with the `if (call.response.writeChannelCancelled) return false`
     * line in [KeelCodecConnectionHandler.processRequest] commented out and confirm the assertion
     * fails; restore it and confirm it passes.
     */
    @Test
    fun `cancel without rethrow closes keep-alive connection before next request — K38b`() {
        val sentinelInvoked = AtomicBoolean(false)

        withKeelServer({
            routing {
                get("/cancel-swallowed") {
                    call.respondBytesWriter {
                        writeFully("data".encodeToByteArray())
                        flush()
                        // Cancel without rethrowing: models explicit early termination such as
                        // SSE handlers that catch client-disconnection and exit cleanly.
                        // Ktor's exception path in respondWriteChannelContent also calls
                        // cancel(cause) before rethrowing, but in that case the exception
                        // propagates through engine.pipeline.execute() and the keep-alive
                        // guard is bypassed — this test covers the no-rethrow variant.
                        cancel(IOException("simulated client disconnect"))
                    }
                }
                get("/sentinel") {
                    // This handler must NOT be reached: after cancel()-without-rethrow the
                    // connection must be closed before the keep-alive loop can read a next head.
                    sentinelInvoked.set(true)
                    call.respondText("sentinel")
                }
            }
        }, keepAlive = true) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 3_000
                val out = socket.getOutputStream()
                val inp = socket.getInputStream()

                // Send both requests back-to-back without waiting for the first response.
                // This ensures /sentinel bytes are already buffered when the server
                // decides whether to close the connection — making the Red-state path
                // deterministic: in Red, the loop reads and processes /sentinel before
                // the encoder crash; in Green, the loop exits before reading /sentinel.
                val req1 = "GET /cancel-swallowed HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n"
                val req2 = "GET /sentinel HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                out.write(req1.toByteArray() + req2.toByteArray())
                out.flush()

                // Drain until EOF. In both fix states this terminates promptly:
                //  Green: server detects writeChannelCancelled → closes connection.
                //  Red: server attempts /sentinel, encoder check fires, closes connection.
                val buf = ByteArray(4096)
                try {
                    while (inp.read(buf) != -1) { /* drain */ }
                } catch (_: java.net.SocketTimeoutException) {
                    // Guard against slow CI — assertion below still catches the Red state
                    // because sentinelInvoked is set before the encoder crash.
                }
            }

            // sentinelInvoked is set before the server tries to write the /sentinel response
            // (Red) or never at all (Green), so no further delay is needed.
            assertFalse(
                sentinelInvoked.get(),
                "K38b: /sentinel handler was invoked — writeChannelCancelled check is absent or not firing; " +
                    "the encoder was left in CHUNKED mode after cancel()-without-rethrow",
            )
        }
    }

    /**
     * K39a regression test: `terminate()` in [AbstractPipelinedWriteChannel] must dispatch
     * [writeTerminator] via [kotlinx.coroutines.CoroutineDispatcher.dispatch] rather than
     * `withContext`, so the terminator task is always enqueued after pending emit tasks —
     * even on Netty whose [io.github.fukusaka.keel.engine.netty.NettyEventLoopDispatcher]
     * returns `isDispatchNeeded() = false` when the caller is already on the EventLoop thread,
     * causing `withContext` to execute the block **inline** ahead of queued emit tasks.
     *
     * **Failure scenario (Red)**: 10-frame SSE handler flushes each frame via
     * [drainAndDispatch], enqueuing emit tasks T1..T10. After the loop,
     * `respondWriteChannelContent`'s `use {}` finally-block calls the Ktor
     * `ByteWriteChannel.close()` extension which fires `flushAndClose()` on the current
     * thread (the EL). In `terminate()`, the pre-fix `withContext(ioDispatcher)` runs
     * `writeTerminator()` **inline** (Netty, `isDispatchNeeded=false`) before T1..T10
     * execute, sending the `HttpBodyEnd` terminator first and leaving the encoder in
     * `NONE` mode. T1..T10 then throw (`HttpBody received without preceding
     * HttpResponseHead`), and the client observes a well-formed HTTP 200 with a 0-byte
     * body.
     *
     * **Post-fix (Green)**: `terminate()` uses
     * `ioDispatcher.dispatch(EmptyCoroutineContext) { writeTerminator() }` which always
     * calls `eventLoop.execute()`, enqueuing the terminator task after T1..T10. All body
     * frames are delivered before the terminator, and the client receives the full payload.
     *
     * Red-Green verification: replace [dispatch] with `withContext(ioDispatcher)` in
     * [AbstractPipelinedWriteChannel.terminate] and confirm this test fails on Netty.
     * Restore [dispatch] and confirm it passes.
     */
    @Test
    fun `K39a — Netty SSE all frames arrive before chunked terminator`() {
        val frameCount = 10
        withKeelNettyServer({
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
                    assertEquals("data: event-$received\n\n", chunk, "K39a: frame $received mismatch")
                    received++
                }
                assertEquals(
                    frameCount,
                    received,
                    "K39a: expected $frameCount frames but got $received — " +
                        "terminate() placed writeTerminator before emit tasks (FIFO violation)",
                )
            }
        }
    }

    // --- Helpers (duplicated from KeelEngineTest to keep this test file self-contained) ---

    private fun withKeelServer(
        module: suspend Application.() -> Unit,
        keepAlive: Boolean = true,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(Keel, port = 0, module = module)
        val cfg = server.engine.configuration
        cfg.engine = NioEngine()
        cfg.keepAlive = keepAlive
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            block(port)
        } finally {
            server.stop(500, 1000)
        }
    }

    private fun withKeelNettyServer(
        module: suspend Application.() -> Unit,
        keepAlive: Boolean = true,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(Keel, port = 0, module = module)
        val cfg = server.engine.configuration
        cfg.engine = NettyEngine()
        cfg.keepAlive = keepAlive
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            block(port)
        } finally {
            server.stop(500, 1000)
        }
    }

    private fun httpGet(port: Int, path: String) {
        val url = URI("http://127.0.0.1:$port$path").toURL()
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.requestMethod = "GET"
            // Drain whatever the server emits before it cancels.
            conn.responseCode
            conn.inputStream.use { stream ->
                runCatching { stream.readBytes() }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun readHttpHeaders(reader: BufferedReader): Map<String, String> {
        reader.readLine() ?: error("EOF before status line")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers.putIfAbsent(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
            }
        }
        return headers
    }

    private fun readNextChunk(reader: BufferedReader): String? {
        val sizeLine = reader.readLine() ?: return null
        val size = sizeLine.trim().toInt(HEX_RADIX)
        if (size == 0) {
            reader.readLine()
            return null
        }
        val buf = CharArray(size)
        var pos = 0
        while (pos < size) {
            val n = reader.read(buf, pos, size - pos)
            if (n == -1) error("Unexpected EOF reading chunk data at offset $pos of $size")
            pos += n
        }
        reader.readLine()
        return String(buf, 0, pos)
    }

    private companion object {
        private const val STREAM_TIMEOUT_MS = 5_000
        private const val HEX_RADIX = 16
    }
}
