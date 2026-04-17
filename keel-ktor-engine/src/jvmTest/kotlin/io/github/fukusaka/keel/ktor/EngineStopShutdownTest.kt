package io.github.fukusaka.keel.ktor

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression tests for `engine.stop()` shutdown paths.
 *
 * Historical context: prior to this fix, `stop()` would block for the full
 * `timeoutMillis` whenever accepted-but-idle keep-alive connections existed
 * at shutdown. Root cause was that `initServerJob` closed `ioEngine` before
 * the per-connection coroutines exited, leaving those coroutines suspended
 * inside `bridge.receiveCatching()` on an already-shut-down EventLoop
 * dispatcher — the outer `serverJob.join()` then could not observe their
 * completion, and `serverJob.cancel()` could not deliver cancellation because
 * the dispatcher was dead. The fix tracks accepted channels and closes them
 * before `ioEngine.close()`, so read loops observe EOF and unwind naturally.
 *
 * Workaround introduced in PR #144 (`Runtime.halt(0)` from the JVM shutdown
 * hook) masked the symptom for benchmark servers but did not address the
 * underlying dangling children.
 */
class EngineStopShutdownTest {

    private fun newServer(module: suspend Application.() -> Unit) =
        embeddedServer(Keel, port = 0, module = module)

    private fun resolvedPort(server: io.ktor.server.engine.EmbeddedServer<*, *>): Int =
        runBlocking { server.engine.resolvedConnectors().first().port }

    @Test
    fun `stop with no clients completes promptly`() {
        val server = newServer { routing { get("/") { call.respondText("ok") } } }
        server.start(wait = false)
        resolvedPort(server)
        val start = System.currentTimeMillis()
        server.stop(500, 1000)
        val elapsed = System.currentTimeMillis() - start
        assertTrue(elapsed < 500, "stop() took too long: $elapsed ms")
    }

    @Test
    fun `stop with 20 idle keep-alive connections completes well under grace period`() {
        val server = newServer { routing { get("/") { call.respondText("ok") } } }
        server.start(wait = false)
        val port = resolvedPort(server)
        // 20 idle keep-alive-style TCP connections — socket is open but no
        // request has been sent. Pre-fix this held `serverJob.join()` for
        // the full timeoutMillis.
        val socks = (1..20).map { Socket("127.0.0.1", port) }
        Thread.sleep(200) // let the server accept all connections
        val start = System.currentTimeMillis()
        server.stop(500, 1000)
        val elapsed = System.currentTimeMillis() - start
        socks.forEach { runCatching { it.close() } }
        // Before the fix: ~1000ms (grace period timeout + forced cancel).
        // After the fix: ~10ms (channels are closed explicitly, read loops
        // observe EOF immediately).
        assertTrue(
            elapsed < 300,
            "stop() with 20 idle connections should complete well under grace period, " +
                "took $elapsed ms (regression: dangling channels?)",
        )
    }

    /**
     * In-flight handler that suspends inside the application (e.g. a long
     * `delay` in a Ktor route) is a known structural limitation: when
     * `ioEngine.close()` shuts down the per-connection EventLoop
     * dispatcher, `cont.cancel()` resumes targeting that dispatcher cannot
     * be delivered. `stop()` therefore relies on `timeoutMillis` to return.
     *
     * The Ktor API contract is still satisfied (stop returns within
     * `timeoutMillis`), so this test pins the current behaviour: total
     * elapsed must be within a small margin of `timeoutMillis` (not
     * indefinite). A future PR could restructure shutdown ordering to
     * close `ioEngine` only after every child coroutine has completed.
     *
     * Note: this test is named to sort last alphabetically (`z*`) so its
     * dangling post-stop handler coroutine does not pollute Dispatchers.Default
     * state for the other tests in this class.
     */
    @Test
    fun `z stop with in-flight suspending handler returns within timeout bound`() {
        val server = newServer {
            routing {
                get("/hang") {
                    kotlinx.coroutines.delay(Long.MAX_VALUE)
                    call.respondText("never")
                }
            }
        }
        server.start(wait = false)
        val port = resolvedPort(server)
        val sock = Socket("127.0.0.1", port)
        sock.getOutputStream().write("GET /hang HTTP/1.1\r\nHost: localhost\r\n\r\n".toByteArray())
        sock.getOutputStream().flush()
        Thread.sleep(200)
        val start = System.currentTimeMillis()
        server.stop(500, 1000)
        val elapsed = System.currentTimeMillis() - start
        runCatching { sock.close() }
        // Not "< 300" like idle connections — the in-flight handler's
        // dispatcher gets shut down before cancellation can reach it, so
        // stop() waits for timeoutMillis and returns. What we pin down is
        // that it does NOT hang beyond the declared timeoutMillis + jitter.
        assertTrue(
            elapsed in 900..1500,
            "stop() with in-flight handler should return within timeoutMillis " +
                "band (900..1500 ms), took $elapsed ms",
        )
    }
}
