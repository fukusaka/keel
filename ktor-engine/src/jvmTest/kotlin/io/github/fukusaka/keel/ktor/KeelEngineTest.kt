package io.github.fukusaka.keel.ktor

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeelEngineTest {

    // --- Basic request/response ---

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

    // --- Keep-alive ---

    @Test
    fun `keep-alive serves multiple requests on same connection`() {
        withKeelServer({ routing { get("/") { call.respondText("OK") } } }) { port ->
            // Use raw socket to control keep-alive behavior
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // First request (HTTP/1.1 default keep-alive)
                writer.print("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
                writer.flush()
                val resp1 = readHttpResponse(reader)
                assertEquals("HTTP/1.1 200 OK", resp1.statusLine)
                assertEquals("OK", resp1.body)
                // No Connection: close header (HTTP/1.1 default is keep-alive)
                assertNull(resp1.headers["Connection"])

                // Second request on same connection
                writer.print("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
                writer.flush()
                val resp2 = readHttpResponse(reader)
                assertEquals("HTTP/1.1 200 OK", resp2.statusLine)
                assertEquals("OK", resp2.body)
            }
        }
    }

    @Test
    fun `Connection close header closes connection after response`() {
        withKeelServer({ routing { get("/") { call.respondText("OK") } } }) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Send request with Connection: close
                writer.print("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                writer.flush()
                val resp = readHttpResponse(reader)
                assertEquals("HTTP/1.1 200 OK", resp.statusLine)
                assertEquals("close", resp.headers["Connection"])

                // Connection should be closed — next read returns EOF
                val nextLine = reader.readLine()
                assertNull(nextLine)
            }
        }
    }

    @Test
    fun `keepAlive=false forces Connection close on every response`() {
        withKeelServer(
            { routing { get("/") { call.respondText("OK") } } },
            keepAlive = false,
        ) { port ->
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 5000
                val writer = PrintWriter(socket.getOutputStream(), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))

                // Even without Connection: close in request, server sends it
                writer.print("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
                writer.flush()
                val resp = readHttpResponse(reader)
                assertEquals("HTTP/1.1 200 OK", resp.statusLine)
                assertEquals("close", resp.headers["Connection"])

                // Connection should be closed
                val nextLine = reader.readLine()
                assertNull(nextLine)
            }
        }
    }

    // --- Dispatcher separation ---

    @Test
    fun `concurrent requests are handled without blocking`() {
        // Verifies that the Ktor pipeline runs on appDispatcher (thread pool),
        // not the EventLoop. If pipeline ran on EventLoop, the delay would
        // block all other connections on the same EventLoop.
        withKeelServer({
            routing {
                get("/slow") {
                    kotlinx.coroutines.delay(200)
                    call.respondText("done")
                }
            }
        }) { port ->
            val threads = (1..5).map { i ->
                Thread {
                    val (status, body) = httpGet(port, "/slow")
                    assertEquals(200, status, "Request $i failed")
                    assertEquals("done", body)
                }
            }
            val start = System.currentTimeMillis()
            threads.forEach { it.start() }
            threads.forEach { it.join(5000) }
            val elapsed = System.currentTimeMillis() - start

            // If serialized on EventLoop: 5 * 200ms = 1000ms minimum.
            // If parallel on thread pool: ~200ms + overhead.
            // Allow generous margin but reject fully serial execution.
            assertTrue(elapsed < 800, "Requests took ${elapsed}ms — likely serialized on EventLoop")
        }
    }

    @Test
    fun `multiple sequential posts with dispatcher separation`() {
        // Exercises the body bridge (EventLoop reads source, Default runs pipeline)
        // across multiple sequential POST requests. Each request verifies that
        // the body is correctly read on EventLoop and processed on Default.
        withKeelServer({
            routing {
                post("/echo") {
                    val body = call.receiveText()
                    call.respondText("echo:$body")
                }
            }
        }) { port ->
            for (i in 1..3) {
                val (status, body) = httpPost(port, "/echo", "body-$i")
                assertEquals(200, status, "Request $i failed")
                assertEquals("echo:body-$i", body)
            }
        }
    }

    @Test
    fun `large payload with dispatcher separation`() {
        // Large response exercises the response channel path where body is
        // streamed via responseChannel() bridge. With dispatcher separation,
        // the bridge runs on the coroutine scope's dispatcher (EventLoop for
        // kqueue/epoll, Default for NIO/others).
        val largeBody = "y".repeat(50_000)
        withKeelServer({
            routing {
                post("/echo-large") {
                    val body = call.receiveText()
                    call.respondText(body)
                }
            }
        }) { port ->
            val (status, body) = httpPost(port, "/echo-large", largeBody)
            assertEquals(200, status)
            assertEquals(50_000, body.length)
            assertEquals(largeBody, body)
        }
    }

    // --- Concurrent + keep-alive ---

    @Test
    fun `concurrent keep-alive connections serve multiple requests each`() {
        // Verifies that multiple keep-alive connections work concurrently.
        // Each connection sends 3 sequential requests on the same socket.
        // Tests the combination of:
        //   - Multiple concurrent accept → handleConnection launches
        //   - Keep-alive loop within each handleConnection
        //   - Body bridge cleanup between keep-alive requests
        val connectionCount = 5
        val requestsPerConnection = 3
        withKeelServer({ routing { get("/") { call.respondText("OK") } } }) { port ->
            val threads = (1..connectionCount).map { connId ->
                Thread {
                    Socket("127.0.0.1", port).use { sock ->
                        sock.soTimeout = 5000
                        val writer = PrintWriter(sock.getOutputStream(), true)
                        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))

                        for (reqId in 1..requestsPerConnection) {
                            writer.print("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n")
                            writer.flush()
                            val response = readHttpResponse(reader)
                            assertEquals(
                                "HTTP/1.1 200 OK", response.statusLine,
                                "conn=$connId req=$reqId",
                            )
                            assertEquals("OK", response.body, "conn=$connId req=$reqId")
                        }
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join(10_000) }
            // Verify all threads completed (not hung)
            threads.forEachIndexed { i, t ->
                assertTrue(!t.isAlive, "Thread $i should have completed")
            }
        }
    }

    // --- Error handling ---

    @Test
    fun `malformed request returns 400 and closes connection`() {
        // Server should respond with 400 Bad Request on malformed HTTP,
        // close the connection, and continue accepting new connections.
        withKeelServer({ routing { get("/") { call.respondText("OK") } } }) { port ->
            // Send garbage that is not valid HTTP
            Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 3000
                val writer = PrintWriter(sock.getOutputStream(), true)
                writer.print("NOT_HTTP\r\n\r\n")
                writer.flush()
                val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                val statusLine = reader.readLine()
                assertNotNull(statusLine, "Server should respond before closing")
                assertTrue(statusLine.contains("400"), "Expected 400 Bad Request, got: $statusLine")
            }
            // Verify server is still alive — next request succeeds
            val (status, body) = httpGet(port, "/")
            assertEquals(200, status)
            assertEquals("OK", body)
        }
    }

    @Test
    fun `client disconnect mid-request does not crash server`() {
        // Client connects, sends partial HTTP, then disconnects.
        // Server should handle the broken connection gracefully
        // and continue accepting new connections.
        withKeelServer({ routing { get("/") { call.respondText("OK") } } }) { port ->
            // Partial request — only send the request line, no headers, then close
            Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 1000
                val writer = PrintWriter(sock.getOutputStream(), true)
                writer.print("GET / HTTP/1.1\r\n")
                writer.flush()
                // Close without sending the empty line terminator
            }
            Thread.sleep(200) // Give server time to detect the disconnect
            // Verify server is still alive
            val (status, body) = httpGet(port, "/")
            assertEquals(200, status)
            assertEquals("OK", body)
        }
    }

    @Test
    fun `empty request closes connection gracefully`() {
        // Client connects but sends nothing, then closes.
        // Server should handle EOF on parseRequestHead gracefully.
        withKeelServer({ routing { get("/") { call.respondText("OK") } } }) { port ->
            Socket("127.0.0.1", port).use { sock ->
                sock.soTimeout = 1000
                // Close immediately without sending anything
            }
            Thread.sleep(200)
            // Verify server is still alive
            val (status, body) = httpGet(port, "/")
            assertEquals(200, status)
            assertEquals("OK", body)
        }
    }

    // --- helpers ---

    private data class HttpResponse(
        val statusLine: String,
        val headers: Map<String, String>,
        val body: String,
    )

    /**
     * Reads a complete HTTP response from a raw socket reader.
     * Handles Content-Length based body reading.
     */
    private fun readHttpResponse(reader: BufferedReader): HttpResponse {
        val statusLine = reader.readLine() ?: error("EOF reading status line")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) {
                headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
            }
        }
        val contentLength = headers["Content-Length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n == -1) break
                read += n
            }
            String(buf, 0, read)
        } else ""
        return HttpResponse(statusLine, headers, body)
    }

    private fun withKeelServer(
        module: suspend Application.() -> Unit,
        keepAlive: Boolean = true,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(Keel, port = 0, module = module)
        // Access Configuration via the engine to set keepAlive
        (server.engine as KeelApplicationEngine).configuration.keepAlive = keepAlive
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
