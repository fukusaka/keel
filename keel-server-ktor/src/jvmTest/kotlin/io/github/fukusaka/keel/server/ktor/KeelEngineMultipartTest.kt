package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.engine.nio.NioEngine
import io.ktor.http.content.forEachPart
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for multipart receive on keel engines.
 *
 * Ktor's built-in `defaultPlatformTransformations` handles [MultiPartData] on JVM
 * but returns null on Native platforms, causing HTTP 415 on Native keel engines.
 * [MultipartReceiveSupport.installMultipartTransform] fills the gap by installing a
 * [CIOMultipartDataBase]-backed transformer as a second [ApplicationStarting] subscriber
 * in [KeelApplicationEngine.init].
 *
 * These JVM tests verify the NIO code path does not regress. Red-Green for the
 * Native regression is verified by `macosArm64Test` / `linuxX64Test`.
 */
class KeelEngineMultipartTest {

    @Test
    fun `multipart upload returns HTTP 200 with part count`() {
        withKeelServer({
            routing {
                post("/multipart-upload") {
                    val parts = call.receiveMultipart()
                    var partCount = 0
                    parts.forEachPart { part ->
                        partCount++
                        part.dispose()
                    }
                    call.respondText("parts=$partCount")
                }
            }
        }) { port ->
            val (status, body) = httpPostMultipart(
                port,
                "/multipart-upload",
                listOf("file" to "hello multipart"),
            )
            assertEquals(200, status, "Expected HTTP 200, got $status (body: $body)")
            assertTrue(body.contains("parts=1"), "Expected parts=1 in: $body")
        }
    }

    @Test
    fun `multipart upload with multiple parts`() {
        withKeelServer({
            routing {
                post("/multipart-upload") {
                    val parts = call.receiveMultipart()
                    var partCount = 0
                    parts.forEachPart { part ->
                        partCount++
                        part.dispose()
                    }
                    call.respondText("parts=$partCount")
                }
            }
        }) { port ->
            val (status, body) = httpPostMultipart(
                port,
                "/multipart-upload",
                listOf(
                    "file1" to "first part",
                    "file2" to "second part",
                    "file3" to "third part",
                ),
            )
            assertEquals(200, status, "Expected HTTP 200, got $status (body: $body)")
            assertTrue(body.contains("parts=3"), "Expected parts=3 in: $body")
        }
    }

    // --- helpers ---

    private fun withKeelServer(
        module: suspend Application.() -> Unit,
        block: (port: Int) -> Unit,
    ) {
        // Loopback, not the default wildcard: SO_REUSEADDR lets another process
        // bind 127.0.0.1 on the same port after this server is already listening
        // on the wildcard, and a connect to 127.0.0.1 then reaches that later,
        // more specific listener instead of this server. Binding loopback makes
        // the second bind fail with EADDRINUSE, so the port cannot be taken over.
        val server = embeddedServer(Keel, host = "127.0.0.1", port = 0, module = module)
        val cfg = (server.engine as KeelApplicationEngine).configuration
        cfg.engine = NioEngine()
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

    private fun httpPostMultipart(
        port: Int,
        path: String,
        parts: List<Pair<String, String>>,
    ): Pair<Int, String> {
        val boundary = "----KeelTestBoundary1234567890"
        val body = buildString {
            parts.forEach { (name, content) ->
                append("--$boundary\r\n")
                append("Content-Disposition: form-data; name=\"$name\"; filename=\"$name.txt\"\r\n")
                append("Content-Type: text/plain\r\n")
                append("\r\n")
                append(content)
                append("\r\n")
            }
            append("--$boundary--\r\n")
        }.toByteArray()
        val url = URI("http://127.0.0.1:$port$path").toURL()
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("Content-Length", body.size.toString())
            connectTimeout = 5000
            readTimeout = 5000
        }
        conn.outputStream.use { it.write(body) }
        val status = conn.responseCode
        val responseBody = if (status in 200..299) {
            conn.inputStream.bufferedReader().readText()
        } else {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        return status to responseBody
    }
}
