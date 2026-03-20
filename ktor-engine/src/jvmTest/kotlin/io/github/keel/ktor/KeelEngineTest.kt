package io.github.keel.ktor

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KeelEngineTest {

    @Test
    fun respondTextHello() {
        withKeelServer({ routing { get("/") { call.respondText("Hello") } } }) { port ->
            val (status, body) = httpGet(port, "/")
            assertEquals(200, status)
            assertEquals("Hello", body)
        }
    }

    @Test
    fun respondStatus404() {
        withKeelServer({ routing { get("/found") { call.respondText("OK") } } }) { port ->
            val (status, _) = httpGet(port, "/not-found")
            assertEquals(404, status)
        }
    }

    @Test
    fun respondWithHeaders() {
        withKeelServer({
            routing {
                get("/headers") {
                    call.response.headers.append("X-Custom", "keel-value")
                    call.respondText("OK")
                }
            }
        }) { port ->
            val conn = openConnection(port, "/headers")
            assertEquals(200, conn.responseCode)
            assertEquals("keel-value", conn.getHeaderField("X-Custom"))
            conn.disconnect()
        }
    }

    @Test
    fun postWithBody() {
        withKeelServer({
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
        val largeText = "x".repeat(100_000)
        withKeelServer({ routing { get("/large") { call.respondText(largeText) } } }) { port ->
            val (status, body) = httpGet(port, "/large")
            assertEquals(200, status)
            assertEquals(100_000, body.length)
        }
    }

    @Test
    fun connectionCloseHeader() {
        withKeelServer({ routing { get("/") { call.respondText("OK") } } }) { port ->
            val conn = openConnection(port, "/")
            assertEquals(200, conn.responseCode)
            assertEquals("close", conn.getHeaderField("Connection"))
            conn.disconnect()
        }
    }

    // --- helpers ---

    private fun withKeelServer(
        module: suspend Application.() -> Unit,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(Keel, port = 0, module = module)
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            block(port)
        } finally {
            server.stop(500, 1000)
        }
    }

    private fun httpGet(port: Int, path: String): Pair<Int, String> {
        val conn = openConnection(port, path)
        val status = conn.responseCode
        val body = if (status in 200..299) conn.inputStream.bufferedReader().readText()
        else conn.errorStream?.bufferedReader()?.readText() ?: ""
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
        val responseBody = if (status in 200..299) conn.inputStream.bufferedReader().readText()
        else conn.errorStream?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return status to responseBody
    }

    private fun openConnection(port: Int, path: String): HttpURLConnection {
        val url = URI("http://127.0.0.1:$port$path").toURL()
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 5000
        }
    }
}
