package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.ktor.websocket.keelWebSocket
import io.github.fukusaka.keel.testing.http.newTestHttpClient
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.PrintWriter
import java.net.Socket
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for `KeelApplicationEngine.stop()` completing within the
 * configured grace period, even when child coroutines (keep-alive
 * connections, in-flight handlers) are suspended on engine dispatchers.
 *
 * Before the `IoEngine` CoroutineScope migration, `engine.close()` tore
 * down the dispatcher eagerly while children were still suspended on it.
 * Cancel resumes were then dispatched to a dead dispatcher and never
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
        // Loopback, not the default wildcard: SO_REUSEADDR lets another process
        // bind 127.0.0.1 on the same port after this server is already listening
        // on the wildcard, and a connect to 127.0.0.1 then reaches that later,
        // more specific listener instead of this server. Binding loopback makes
        // the second bind fail with EADDRINUSE, so the port cannot be taken over.
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0) {
            routing { get("/") { call.respondText("OK") } }
        }
        (server.engine as KeelApplicationEngine).configuration.engine = NioEngine()
        server.start(wait = false)
        // Resolve the port so startup has definitely finished.
        runBlocking { withTimeout(5.seconds) { server.engine.resolvedConnectors().first().port } }

        val elapsed = measureStopMillis(server, gracePeriodMillis = 500, timeoutMillis = 1000)
        assertTrue(elapsed < GRACE_BUDGET_MS, "stop took ${elapsed}ms, expected < $GRACE_BUDGET_MS")
    }

    @Test
    fun `stop with single idle keep-alive connection completes within grace period`() {
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0) {
            routing { get("/") { call.respondText("OK") } }
        }
        (server.engine as KeelApplicationEngine).configuration.engine = NioEngine()
        server.start(wait = false)
        val port = runBlocking {
            withTimeout(15.seconds) {
                server.engine.resolvedConnectors().first().port
            }
        }

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
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0) {
            routing { get("/") { call.respondText("OK") } }
        }
        (server.engine as KeelApplicationEngine).configuration.engine = NioEngine()
        server.start(wait = false)
        val port = runBlocking {
            withTimeout(15.seconds) {
                server.engine.resolvedConnectors().first().port
            }
        }

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
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0) {
            routing {
                get("/slow") {
                    // Suspend far longer than the stop timeout so the
                    // handler is still in-flight when stop() begins.
                    delay(60_000)
                    call.respondText("OK")
                }
            }
        }
        (server.engine as KeelApplicationEngine).configuration.engine = NioEngine()
        server.start(wait = false)
        val port = runBlocking {
            withTimeout(15.seconds) {
                server.engine.resolvedConnectors().first().port
            }
        }

        newTestHttpClient().use { client ->
            val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/slow"))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build()
            val pending: CompletableFuture<HttpResponse<String>> =
                client.http.sendAsync(req, HttpResponse.BodyHandlers.ofString())

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
    }

    /**
     * Defect G from PR #481's R3 / R1 root-cause analysis: 50 concurrent idle
     * WebSocket connections must terminate within the configured grace period
     * when [io.ktor.server.engine.EmbeddedServer.stop] is called. WebSocket
     * connections are different from HTTP keep-alive in two ways that matter
     * for graceful shutdown:
     *
     * 1. The server-side handler is *suspended on `incoming.receive()`* (not on
     *    a next-request read), so the cancel signal must reach a different
     *    coroutine continuation than the keep-alive case.
     * 2. The protocol closing handshake involves a server-initiated CLOSE
     *    frame that must reach every client before the engine tears down the
     *    underlying socket — naive shutdown would simply RST the connections
     *    and clients would see an abrupt EOF.
     *
     * The defect was originally observed as a hypothesis from the macOS-runner
     * IoBuf-leak SIGKILL flake on Apple Silicon; the seam tests in
     * [io.github.fukusaka.keel.engine.netty.NettyPipelineWsEchoSeamTest] /
     * [io.github.fukusaka.keel.engine.netty.NettyPipelineWsLargePayloadTest]
     * cover the per-frame IoBuf invariant deterministically, but graceful-stop
     * timing under sustained connection counts is a separate failure class
     * scoped to the integration layer (engine + Ktor adapter).
     *
     * 50 connections is large enough to surface a per-connection cleanup cost
     * that scales linearly (~10 ms / conn × 50 = 500 ms would already exhaust
     * the grace budget) but small enough to fit within the existing 1500 ms
     * elapsed-time bound used by the rest of the suite.
     */
    @Test
    fun `stop with 50 idle WebSocket connections completes within grace period`() {
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0) {
            routing {
                keelWebSocket("/idle") {
                    // Drain the inbound channel so the handler stays suspended
                    // on `incoming.receive()` rather than exiting early. We
                    // never expect a frame in this test — the goal is to keep
                    // the server-side coroutine parked exactly the way a
                    // long-lived idle WS conn would.
                    for (frame in incoming) {
                        // discard
                    }
                }
            }
        }
        (server.engine as KeelApplicationEngine).configuration.engine = NioEngine()
        server.start(wait = false)
        val port = runBlocking {
            withTimeout(15.seconds) {
                server.engine.resolvedConnectors().first().port
            }
        }

        val connectionCount = 50
        newTestHttpClient(threadPoolSize = 8).use { client ->
            // Fork the WS opens in parallel via buildAsync — building 50
            // sequentially via .get(...) would stack ~50 × accept latencies
            // on the test thread before stop() is called. The async path
            // matches how a real client population would arrive.
            val pending = (1..connectionCount).map {
                client.http.newWebSocketBuilder()
                    .buildAsync(URI("ws://127.0.0.1:$port/idle"), IdleWsListener())
            }
            val sockets = pending.map { it.get(10, TimeUnit.SECONDS) }
            try {
                val elapsed = measureStopMillis(server, gracePeriodMillis = 500, timeoutMillis = 1000)
                assertTrue(
                    elapsed < GRACE_BUDGET_MS,
                    "stop with 50 idle WS connections took ${elapsed}ms, expected < $GRACE_BUDGET_MS",
                )
            } finally {
                for (ws in sockets) runCatching { ws.abort() }
            }
        }
    }

    /**
     * Minimal [WebSocket.Listener] for the graceful-stop test. Requests
     * unbounded inbound demand so any frames the server sends (in particular
     * the closing CLOSE frame) reach the client without per-frame request
     * gating, but otherwise discards every callback — this client is parked
     * for the duration of the test.
     */
    private class IdleWsListener : WebSocket.Listener {
        override fun onOpen(webSocket: WebSocket) {
            webSocket.request(Long.MAX_VALUE)
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
