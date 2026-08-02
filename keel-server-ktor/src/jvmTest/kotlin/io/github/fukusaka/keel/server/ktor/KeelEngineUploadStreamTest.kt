package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.engine.netty.NettyEngine
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.discard
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for request body streaming via call.receiveChannel().
 *
 * The `/upload-stream` benchmark endpoint on `ktor-keel-netty` returned 0 bytes
 * received for all payload sizes. The root cause was that the body pump coroutine
 * exited early (via `result.isClosed`) before writing all body bytes to the pipe,
 * so [io.ktor.utils.io.ByteReadChannel.discard] returned 0 instead of the full size.
 *
 * Red-Green: the Netty test was failing before the fix; NIO test guards against
 * regression on the pull-based engine path.
 */
class KeelEngineUploadStreamTest {

    @Test
    fun `NIO upload-stream returns correct byte count`() {
        withUploadServer(useNetty = false) { port ->
            for (size in listOf(1, 1024, 64 * 1024, 512 * 1024)) {
                val received = httpPostAndGetBytesReceived(port, size)
                assertEquals(size, received, "NIO: expected $size bytes, got $received")
            }
        }
    }

    @Test
    fun `Netty upload-stream returns correct byte count`() {
        withUploadServer(useNetty = true) { port ->
            for (size in listOf(1, 1024, 64 * 1024, 512 * 1024)) {
                val received = httpPostAndGetBytesReceived(port, size)
                assertEquals(size, received, "Netty: expected $size bytes, got $received")
            }
        }
    }

    @Test
    fun `Netty upload-stream with multiple sequential requests`() {
        withUploadServer(useNetty = true) { port ->
            repeat(5) { i ->
                val size = (i + 1) * 10_000
                val received = httpPostAndGetBytesReceived(port, size)
                assertEquals(size, received, "Netty sequential req ${i + 1}: expected $size bytes")
            }
        }
    }

    // --- helpers ---

    private fun withUploadServer(useNetty: Boolean, block: (port: Int) -> Unit) {
        val module: suspend Application.() -> Unit = {
            routing {
                post("/upload-stream") {
                    val received = call.receiveChannel().discard()
                    call.response.headers.append("X-Bytes-Received", received.toString())
                    call.respondBytes(byteArrayOf())
                }
            }
        }
        // Loopback, not the default wildcard: SO_REUSEADDR lets another process
        // bind 127.0.0.1 on the same port after this server is already listening
        // on the wildcard, and a connect to 127.0.0.1 then reaches that later,
        // more specific listener instead of this server. Binding loopback makes
        // the second bind fail with EADDRINUSE, so the port cannot be taken over.
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0, module = module)
        val cfg = (server.engine as KeelApplicationEngine).configuration
        cfg.engine = if (useNetty) NettyEngine() else NioEngine()
        server.start(wait = false)
        try {
            val port = runBlocking {
                withTimeout(15.seconds) {
                    server.engine.resolvedConnectors().first().port
                }
            }
            block(port)
        } finally {
            server.stop(500, 1000)
        }
    }

    private fun httpPostAndGetBytesReceived(port: Int, bodySize: Int): Int {
        val body = ByteArray(bodySize) { 'x'.code.toByte() }
        val url = URI("http://127.0.0.1:$port/upload-stream").toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Content-Length", bodySize.toString())
            connectTimeout = 5000
            readTimeout = 10000
        }
        conn.outputStream.use { it.write(body) }
        conn.responseCode // trigger the request
        val received = conn.getHeaderField("X-Bytes-Received")?.toIntOrNull() ?: -1
        conn.disconnect()
        return received
    }
}
