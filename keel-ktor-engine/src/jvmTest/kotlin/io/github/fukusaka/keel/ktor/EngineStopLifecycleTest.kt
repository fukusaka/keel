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
 * Regression tests for the `engine.stop()` shutdown lifecycle.
 *
 * Before the lifecycle fix, `initServerJob`'s body called `ioEngine.close()`
 * as its final statement. That shut down the per-connection EventLoop
 * dispatcher before `handleConnection` children had completed: children
 * still suspended (inside `bridge.receiveCatching()` on an idle keep-alive
 * read, or inside a long-running route like `delay(Long.MAX_VALUE)`) could
 * no longer receive cancellation resumption because their dispatcher was
 * gone. `serverJob.join()` could not observe them complete, and stop waited
 * for the full `timeoutMillis` before returning — in benchmark scenarios,
 * PR #144 then fell back to `Runtime.halt(0)` to terminate the JVM.
 *
 * The fix waits for every child coroutine to finish (either naturally, or
 * via explicit `cancelAndJoin` under `NonCancellable`) *before* closing
 * `ioEngine`. With the dispatcher still alive at cancel time, cancellation
 * reaches every suspended child reliably.
 *
 * These tests pin the post-fix timing:
 *   - no-client / idle-connection / 20-idle-connection: complete well
 *     within the grace period (there is nothing to cancel, or cancellation
 *     completes promptly).
 *   - in-flight suspending handler: cancellation reaches `delay()` while
 *     its dispatcher is still alive, so the handler unwinds via
 *     `CancellationException` and `stop()` likewise returns within the
 *     grace period — the pre-fix behaviour of relying on `timeoutMillis`
 *     is gone.
 */
class EngineStopLifecycleTest {

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
    fun `stop with single idle connection completes within grace period`() {
        val server = newServer { routing { get("/") { call.respondText("ok") } } }
        server.start(wait = false)
        val port = resolvedPort(server)
        val sock = Socket("127.0.0.1", port)
        Thread.sleep(100)
        val start = System.currentTimeMillis()
        server.stop(500, 1000)
        val elapsed = System.currentTimeMillis() - start
        runCatching { sock.close() }
        assertTrue(elapsed < 700, "stop() took too long: $elapsed ms")
    }

    @Test
    fun `stop with 20 idle keep-alive connections completes within grace period`() {
        val server = newServer { routing { get("/") { call.respondText("ok") } } }
        server.start(wait = false)
        val port = resolvedPort(server)
        val socks = (1..20).map { Socket("127.0.0.1", port) }
        Thread.sleep(200)
        val start = System.currentTimeMillis()
        server.stop(500, 1000)
        val elapsed = System.currentTimeMillis() - start
        socks.forEach { runCatching { it.close() } }
        // Before the lifecycle fix: ~1000 ms (full timeoutMillis).
        // After: cancelation runs while the dispatcher is still alive, so
        // every handleConnection unwinds via CancellationException and the
        // join completes well within the grace period.
        assertTrue(elapsed < 700, "stop() took too long: $elapsed ms")
    }

    @Test
    fun `stop with in-flight suspending handler completes within grace period`() {
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
        // Before the lifecycle fix: ~1000 ms (handler stranded on a dead
        // dispatcher, `stop()` waited for `timeoutMillis`).
        // After: the handler's `delay(Long.MAX_VALUE)` is cancelled while
        // its dispatcher is still alive, so it unwinds promptly.
        assertTrue(
            elapsed < 700,
            "stop() with in-flight suspending handler should unwind via " +
                "cancellation within grace period, took $elapsed ms",
        )
    }
}
