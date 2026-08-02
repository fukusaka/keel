package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * Multi-connector coverage for the HTTP server DSL: repeated
 * `connector { }` blocks become one multi-address server (bind-order
 * address reporting, the same routes served on every port), and the
 * server-wide HTTP-semantics settings inside the block reject a second
 * non-default setter instead of silently letting the last writer win.
 */
class KeelHttpServerMultiConnectorTest {

    private fun httpGet(port: Int, path: String): Pair<Int, String> {
        val conn = URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection
        return try {
            val code = conn.responseCode
            val body = conn.inputStream.readBytes().decodeToString()
            code to body
        } finally {
            conn.disconnect()
        }
    }

    @Test
    fun `two connectors serve the same routes and report addresses in declaration order`() = runBlocking {
        withTimeout(15.seconds) {
            val server = keelHttpServer(NioEngine()) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                get("/hello") { call -> call.respondText("hi") }
            }
            server.start()
            try {
                assertEquals(2, server.localAddresses.size)
                assertEquals(server.localAddresses.first(), server.localAddress)
                val ports = server.localAddresses.map { (it as InetSocketAddress).port }
                assertEquals(2, ports.distinct().size)
                for (port in ports) {
                    val (code, body) = httpGet(port, "/hello")
                    assertEquals(200, code)
                    assertEquals("hi", body)
                }
            } finally {
                server.stop()
            }
        }
    }

    @Test
    fun `a server-wide setting set by two connector blocks is rejected`() {
        assertFailsWith<IllegalStateException> {
            keelHttpServer(NioEngine()) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    headerTimeoutMillis = 5_000
                }
                connector {
                    host = "127.0.0.1"
                    port = 0
                    headerTimeoutMillis = 10_000
                }
            }
        }
    }

    @Test
    fun `a server-wide setting from one block applies alongside other connectors`() = runBlocking {
        withTimeout(15.seconds) {
            val server = keelHttpServer(NioEngine()) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                    headerTimeoutMillis = 5_000
                }
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                get("/ok") { call -> call.respondText("ok") }
            }
            server.start()
            try {
                val ports = server.localAddresses.map { (it as InetSocketAddress).port }
                for (port in ports) {
                    assertEquals(200, httpGet(port, "/ok").first)
                }
            } finally {
                server.stop()
            }
        }
    }
}
