package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.engine.nio.NioEngine
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.utils.io.ClosedWriteChannelException
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
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

    // --- Helpers (duplicated from KeelEngineTest to keep this test file self-contained) ---

    private fun withKeelServer(
        module: suspend Application.() -> Unit,
        keepAlive: Boolean = true,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(Keel, port = 0, module = module)
        val cfg = (server.engine as KeelApplicationEngine).configuration
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
