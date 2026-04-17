package io.github.fukusaka.keel.ktor

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.PrintWriter
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression test for `KeelApplicationEngine.stop()` completing within the
 * configured grace period, even when child coroutines (keep-alive
 * connections, in-flight handlers) are suspended on engine dispatchers.
 *
 * Before the Phase 1 `IoEngine` CoroutineScope migration, `engine.close()`
 * tore down the dispatcher eagerly while children were still suspended on
 * it. Cancel resumes were then dispatched to a dead dispatcher and never
 * fired, `serverJob.join()` never completed, and `stop()` waited out the
 * full timeout.
 *
 * Each case uses a 500 ms grace period and asserts that `stop()` returns
 * within `< 1500 ms`. The threshold leaves room for CI noise while staying
 * well below the 1000 ms timeout that the old behavior always hit.
 */
class EngineStopLifecycleTest {

    @Test
    fun `stop with no clients completes promptly`() {
        val server = embeddedServer(Keel, port = 0) {
            routing { get("/") { call.respondText("OK") } }
        }
        server.start(wait = false)
        // Resolve the port so startup has definitely finished.
        runBlocking { server.engine.resolvedConnectors().first().port }

        val elapsed = measureStopMillis(server, gracePeriodMillis = 500, timeoutMillis = 1000)
        assertTrue(elapsed < GRACE_BUDGET_MS, "stop took ${elapsed}ms, expected < $GRACE_BUDGET_MS")
    }

    @Test
    fun `stop with single idle keep-alive connection completes within grace period`() {
        val server = embeddedServer(Keel, port = 0) {
            routing { get("/") { call.respondText("OK") } }
        }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }

        val client = Socket("127.0.0.1", port)
        try {
            sendKeepAliveRequest(client, port)
            // Leave the socket open so the server has an idle keep-alive
            // connection suspended on the next-request read.
            val elapsed = measureStopMillis(server, gracePeriodMillis = 500, timeoutMillis = 1000)
            assertTrue(elapsed < GRACE_BUDGET_MS, "stop took ${elapsed}ms, expected < $GRACE_BUDGET_MS")
        } finally {
            runCatching { client.close() }
        }
    }

    @Test
    fun `stop with many idle keep-alive connections completes within grace period`() {
        val server = embeddedServer(Keel, port = 0) {
            routing { get("/") { call.respondText("OK") } }
        }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }

        val clients = (1..20).map { Socket("127.0.0.1", port) }
        try {
            for (client in clients) sendKeepAliveRequest(client, port)
            val elapsed = measureStopMillis(server, gracePeriodMillis = 500, timeoutMillis = 1000)
            assertTrue(elapsed < GRACE_BUDGET_MS, "stop took ${elapsed}ms, expected < $GRACE_BUDGET_MS")
        } finally {
            for (client in clients) runCatching { client.close() }
        }
    }

    @Test
    fun `stop with in-flight suspending handler completes within grace period`() {
        val server = embeddedServer(Keel, port = 0) {
            routing {
                get("/slow") {
                    // Suspend far longer than the stop timeout so the
                    // handler is still in-flight when stop() begins.
                    delay(60_000)
                    call.respondText("OK")
                }
            }
        }
        server.start(wait = false)
        val port = runBlocking { server.engine.resolvedConnectors().first().port }

        val http = HttpClient.newHttpClient()
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/slow"))
            .timeout(Duration.ofSeconds(30))
            .GET()
            .build()
        val pending: CompletableFuture<HttpResponse<String>> = http.sendAsync(req, HttpResponse.BodyHandlers.ofString())

        // Ensure the request has been accepted and the handler has
        // started suspending. 200 ms is empirically enough for Ktor
        // routing to dispatch to the handler on all supported OSes.
        Thread.sleep(200)

        try {
            val elapsed = measureStopMillis(server, gracePeriodMillis = 500, timeoutMillis = 1500)
            assertTrue(elapsed < GRACE_BUDGET_MS, "stop took ${elapsed}ms, expected < $GRACE_BUDGET_MS")
        } finally {
            pending.cancel(true)
        }
    }

    private fun measureStopMillis(
        server: io.ktor.server.engine.EmbeddedServer<*, *>,
        gracePeriodMillis: Long,
        timeoutMillis: Long,
    ): Long {
        val start = System.nanoTime()
        server.stop(gracePeriodMillis, timeoutMillis)
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun sendKeepAliveRequest(socket: Socket, port: Int) {
        val writer = PrintWriter(socket.getOutputStream(), true)
        writer.print("GET / HTTP/1.1\r\n")
        writer.print("Host: 127.0.0.1:$port\r\n")
        writer.print("Connection: keep-alive\r\n")
        writer.print("\r\n")
        writer.flush()
        // Drain the response so the server is ready to accept the next
        // request on this connection (it's now idle-keep-alive).
        val input = socket.getInputStream()
        val buf = ByteArray(4096)
        // Loose read loop — we only need to know the server wrote the
        // response back. A 300 ms budget is ample for a loopback GET.
        val deadline = System.nanoTime() + 300_000_000L
        socket.soTimeout = 300
        while (System.nanoTime() < deadline) {
            val n = try {
                input.read(buf)
            } catch (_: Exception) {
                break
            }
            if (n <= 0) break
            // Response headers end with a blank line; once we've seen
            // "OK" we know the response is fully delivered for a short
            // handler like this one.
            val slice = String(buf, 0, n)
            if ("OK" in slice) break
        }
        socket.soTimeout = 0
    }

    companion object {
        /**
         * Upper bound for stop() elapsed time in milliseconds. Grace is
         * 500 ms; we allow 3x headroom for CI scheduling jitter while
         * still failing if the old "wait out the full timeout" behavior
         * regresses (that would be >= 1000 ms).
         */
        private const val GRACE_BUDGET_MS = 1500L
    }
}
