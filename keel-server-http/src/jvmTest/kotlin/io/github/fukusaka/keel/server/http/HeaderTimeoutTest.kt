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
 * End-to-end integration test for the header-complete deadline
 * ([io.github.fukusaka.keel.server.http.dsl.HttpConnectorBuilder.headerTimeoutMillis]),
 * the codec-layer defence against slow-header (classic slowloris) trickle attacks.
 *
 * Real `keelHttpServer` on a real engine with a raw [Socket] peer: a peer that
 * trickles a request head without ever completing it is force-closed once the
 * absolute deadline elapses (an inactivity timeout could not stop this — each
 * trickled byte would refresh it), while a peer that completes the head within the
 * budget is served normally.
 */
class HeaderTimeoutTest {

    @Test
    fun `a slow-header peer that never completes the head is force-closed`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    headerTimeoutMillis = HEADER_TIMEOUT_MS
                }
                get("/") { call -> call.respondText("ok") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            val client = Socket(InetAddress.getLoopbackAddress(), port)
            client.soTimeout = 5_000
            try {
                // Start a request, then trickle header bytes every GAP_MS (< the timeout,
                // so an inactivity timer would never fire) WITHOUT the terminating CRLF
                // that completes the head. The absolute header deadline must still close
                // the connection, so a read observes EOF (or a reset).
                val out = client.getOutputStream()
                out.write("GET / HTTP/1.1\r\n".toByteArray())
                out.flush()
                val slowHeader = "X-Slow: aaaaaaaaaaaaaaaa"
                for (ch in slowHeader) {
                    runCatching {
                        out.write(ch.code)
                        out.flush()
                    }
                    Thread.sleep(GAP_MS)
                }
                val closed = readUntilClosed(client)
                assertTrue(closed, "the server must force-close a peer that never completes the request head")
            } finally {
                client.close()
                server.stop()
                engine.close()
            }
        }
    }

    @Test
    fun `a head completed within the budget is served normally`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    headerTimeoutMillis = HEADER_TIMEOUT_MS
                }
                get("/") { call -> call.respondText("ok") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            val client = Socket(InetAddress.getLoopbackAddress(), port)
            client.soTimeout = 5_000
            try {
                // The whole head arrives at once, well within the budget.
                client.getOutputStream().write("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
                client.getOutputStream().flush()
                val statusLine = client.getInputStream().bufferedReader().readLine()
                assertEquals("HTTP/1.1 200 OK", statusLine, "a timely head should get a normal response")
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
                return false // open but idle within the read timeout
            } catch (_: java.io.IOException) {
                return true // connection reset = closed
            }
        }
        return false
    }

    private companion object {
        const val HEADER_TIMEOUT_MS = 500L
        const val GAP_MS = 120L // < HEADER_TIMEOUT_MS so an inactivity timer would never fire
        const val MAX_READS = 200
    }
}
