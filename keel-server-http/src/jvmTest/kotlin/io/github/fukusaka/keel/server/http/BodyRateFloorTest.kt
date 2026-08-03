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
 * End-to-end integration test for the body rate floor
 * ([io.github.fukusaka.keel.server.http.dsl.HttpConnectorBuilder.minBodyRateBytesPerSec]),
 * the fine-grained slow-body defence that distinguishes a legitimate slow upload from a
 * trickle attack — the discrimination a single generous absolute ceiling cannot make.
 *
 * Real `keelHttpServer` on a real engine with a raw [Socket] peer, with the absolute
 * request-total deadline disabled so the only thing that can close the connection is the
 * rate floor: a peer trickling its body far below the floor is force-closed, while a peer
 * uploading steadily above the floor is served normally.
 */
class BodyRateFloorTest {

    @Test
    fun `a body trickling below the rate floor is force-closed`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    // Only the rate floor is active; no absolute deadline.
                    minBodyRateBytesPerSec = HIGH_FLOOR
                }
                post("/") { call -> call.respondText("ok") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            val client = Socket(InetAddress.getLoopbackAddress(), port)
            client.soTimeout = 5_000
            try {
                // A complete head declaring a large body, then a 1-byte-per-GAP_MS trickle
                // (~8 B/s) — far below the HIGH_FLOOR (1000 B/s). No inactivity gap ever
                // elapses, and no absolute deadline is set, yet the rate floor must close
                // the connection once a window comes up short.
                val out = client.getOutputStream()
                out.write("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                out.flush()
                repeat(TRICKLE_BYTES) {
                    runCatching {
                        out.write('a'.code)
                        out.flush()
                    }
                    Thread.sleep(GAP_MS)
                }
                assertTrue(readUntilClosed(client), "the server must force-close a body trickling below the rate floor")
            } finally {
                client.close()
                server.stop()
                engine.close()
            }
        }
    }

    @Test
    fun `a body uploaded steadily above the rate floor is served normally`() = runBlocking {
        withTimeout(15.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    minBodyRateBytesPerSec = LOW_FLOOR
                }
                post("/") { call -> call.respondText("ok") }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            val client = Socket(InetAddress.getLoopbackAddress(), port)
            client.soTimeout = 5_000
            try {
                // A slow but honest upload: CHUNK bytes every STEADY_GAP_MS spans more than
                // one window (so the floor check actually runs), at ~400 B/s — comfortably
                // above the LOW_FLOOR (50 B/s). It must be served, not killed.
                val out = client.getOutputStream()
                val total = CHUNK * STEADY_CHUNKS
                out.write("POST / HTTP/1.1\r\nHost: x\r\nContent-Length: $total\r\n\r\n".toByteArray())
                out.flush()
                repeat(STEADY_CHUNKS) {
                    out.write(ByteArray(CHUNK) { 'a'.code.toByte() })
                    out.flush()
                    Thread.sleep(STEADY_GAP_MS)
                }
                val statusLine = client.getInputStream().bufferedReader().readLine()
                assertEquals("HTTP/1.1 200 OK", statusLine, "an honest steady upload above the floor should be served")
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
        // Attack case: require 1000 B/s; the 1-byte trickle delivers ~8 B/s.
        const val HIGH_FLOOR = 1_000L
        const val GAP_MS = 120L
        const val TRICKLE_BYTES = 20 // 20 × 120 ms = 2.4 s > the 1 s window

        // Honest case: require only 50 B/s; the steady upload delivers ~400 B/s.
        const val LOW_FLOOR = 50L
        const val CHUNK = 200
        const val STEADY_GAP_MS = 500L
        const val STEADY_CHUNKS = 6 // 6 × 200 B over ~3 s spans several windows

        const val MAX_READS = 200
    }
}
