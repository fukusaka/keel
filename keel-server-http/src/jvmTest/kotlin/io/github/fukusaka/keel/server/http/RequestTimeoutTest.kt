package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration test for the request-total deadline
 * ([io.github.fukusaka.keel.server.http.dsl.HttpConnectorBuilder.requestTimeoutMillis]),
 * the absolute ceiling that defends against slow-body trickle attacks the transport
 * idle timeout cannot stop (each trickled body byte refreshes an inactivity timer,
 * but not this absolute deadline).
 *
 * Real `keelHttpServer` on a real engine with a raw [Socket] peer: a peer that sends
 * a complete head then trickles the declared body without finishing is force-closed
 * once the request-total deadline elapses, while a peer that completes the whole
 * request within the budget is served normally.
 */
class RequestTimeoutTest {

    @Test
    fun `a slow-body peer that never completes the request is force-closed`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                }
                post("/") { call -> call.respondText("ok") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            val client = Socket(InetAddress.getLoopbackAddress(), port)
            client.soTimeout = 5_000
            try {
                // Send a complete head declaring a body, then trickle body bytes every
                // GAP_MS (< the timeout, so an inactivity timer would never fire) and
                // never reach the declared length. The absolute request-total deadline
                // (armed at the first request byte) must still close the connection.
                val out = client.getOutputStream()
                out.write("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 1000\r\n\r\n".toByteArray())
                out.flush()
                repeat(SLOW_BODY_BYTES) {
                    runCatching {
                        out.write('a'.code)
                        out.flush()
                    }
                    Thread.sleep(GAP_MS)
                }
                assertTrue(
                    readUntilClosed(client),
                    "the server must force-close a peer that never completes the request body",
                )
            } finally {
                client.close()
                server.stop()
                engine.close()
            }
        }
    }

    @Test
    fun `a request completed within the budget is served normally`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    requestTimeoutMillis = REQUEST_TIMEOUT_MS
                }
                post("/") { call -> call.respondText("ok") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            val client = Socket(InetAddress.getLoopbackAddress(), port)
            client.soTimeout = 5_000
            try {
                // The whole request (head + body) arrives at once, well within the budget.
                client.getOutputStream().write(
                    "POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 3\r\n\r\nabc".toByteArray(),
                )
                client.getOutputStream().flush()
                val statusLine = client.getInputStream().bufferedReader().readLine()
                assertEquals("HTTP/1.1 200 OK", statusLine, "a timely request should get a normal response")
            } finally {
                client.close()
                server.stop()
                engine.close()
            }
        }
    }

    /** Drains until EOF (-1) or a reset, bounded so a non-closing bug fails rather than hangs. */
    private fun readUntilClosed(client: Socket): Boolean {
        val ins = client.getInputStream()
        val buf = ByteArray(1024)
        var reads = 0
        while (reads < MAX_READS) {
            reads++
            try {
                if (ins.read(buf) == -1) return true
            } catch (_: SocketTimeoutException) {
                return false
            } catch (_: java.io.IOException) {
                return true // connection reset = closed
            }
        }
        return false
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 500L
        const val GAP_MS = 120L // < REQUEST_TIMEOUT_MS so an inactivity timer would never fire
        const val SLOW_BODY_BYTES = 8 // 8 × 120 ms = 960 ms > the 500 ms request-total deadline
        const val MAX_READS = 200
    }
}
