package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.engine.nio.NioEngine
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for the `KeelCio` factory — `embeddedServer(KeelCio)` driving
 * the keel transport stack via [NioEngine] but parsing requests with
 * `ktor-http-cio`.  Covers the basic request/response shapes that the bench
 * scenarios exercise (`/hello`, `/echo`, `/large`).
 */
class KeelCioEngineTest {

    @Test
    fun respondTextHello() {
        withKeelCioServer({ routing { get("/") { call.respondText("Hello") } } }) { port ->
            val (status, body) = httpGet(port, "/")
            assertEquals(200, status)
            assertEquals("Hello", body)
        }
    }

    @Test
    fun respondStatus404() {
        withKeelCioServer({ routing { get("/found") { call.respondText("OK") } } }) { port ->
            val (status, _) = httpGet(port, "/not-found")
            assertEquals(404, status)
        }
    }

    @Test
    fun postWithBody() {
        withKeelCioServer({
            routing {
                post("/echo") {
                    val body = call.receiveText()
                    call.respondText("echo:$body")
                }
            }
        }) { port ->
            val (status, body) = httpPost(port, "/echo", "hello-body")
            assertEquals(200, status)
            assertEquals("echo:hello-body", body)
        }
    }

    @Test
    fun largeResponse() {
        val largeText = "x".repeat(LARGE_PAYLOAD_BYTES)
        withKeelCioServer({ routing { get("/large") { call.respondText(largeText) } } }) { port ->
            val (status, body) = httpGet(port, "/large")
            assertEquals(200, status)
            assertEquals(LARGE_PAYLOAD_BYTES, body.length)
            assertTrue(body.all { it == 'x' })
        }
    }

    @Test
    fun respondWithCustomHeader() {
        withKeelCioServer({
            routing {
                get("/headers") {
                    call.response.headers.append("X-Custom", "keel-cio-value")
                    call.respondText("OK")
                }
            }
        }) { port ->
            val conn = openConnection(port, "/headers")
            assertEquals(200, conn.responseCode)
            assertEquals("keel-cio-value", conn.getHeaderField("X-Custom"))
            conn.disconnect()
        }
    }

    @Test
    fun keepAliveAcrossMultipleRequests() {
        withKeelCioServer({
            routing { get("/ping") { call.respondText("pong") } }
        }) { port ->
            // Two sequential requests on the same connection — the second
            // would fail if the keep-alive loop didn't reset cleanly.
            repeat(KEEPALIVE_ROUND_TRIPS) {
                val (status, body) = httpGet(port, "/ping")
                assertEquals(200, status)
                assertEquals("pong", body)
            }
        }
    }

    private fun withKeelCioServer(
        module: suspend Application.() -> Unit,
        keepAlive: Boolean = true,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(KeelCio, port = 0, module = module)
        val cfg = server.engine.configuration
        cfg.engine = NioEngine()
        cfg.keepAlive = keepAlive
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            block(port)
        } finally {
            server.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
        }
    }

    private fun httpGet(port: Int, path: String): Pair<Int, String> {
        val conn = openConnection(port, path)
        val status = conn.responseCode
        val body = if (status in TWO_HUNDRED..TWO_NINETY_NINE) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        return status to body
    }

    private fun httpPost(port: Int, path: String, body: String): Pair<Int, String> {
        val conn = openConnection(port, path)
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "text/plain")
        conn.setRequestProperty("Content-Length", body.length.toString())
        conn.outputStream.use { it.write(body.toByteArray()) }
        val status = conn.responseCode
        val responseBody = if (status in TWO_HUNDRED..TWO_NINETY_NINE) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        return status to responseBody
    }

    private fun openConnection(port: Int, path: String): HttpURLConnection {
        val url = URI("http://127.0.0.1:$port$path").toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
    }

    private companion object {
        private const val LARGE_PAYLOAD_BYTES = 100_000
        private const val KEEPALIVE_ROUND_TRIPS = 5
        private const val SHUTDOWN_GRACE_MS = 500L
        private const val SHUTDOWN_TIMEOUT_MS = 1000L
        private const val CONNECT_TIMEOUT_MS = 5000
        private const val READ_TIMEOUT_MS = 5000
        private const val TWO_HUNDRED = 200
        private const val TWO_NINETY_NINE = 299
    }
}
