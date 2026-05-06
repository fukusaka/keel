package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.engine.nio.NioEngine
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
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
}
