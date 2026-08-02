package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.testing.http.newTestHttpClient
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for WebSocket support via the `KeelCio` factory using the
 * standard Ktor [WebSockets] plugin (the `respondUpgrade` path in
 * [KeelCioApplicationResponse]).
 */
class KeelCioWebSocketTest {

    @Test
    fun echoText() {
        withKeelCioServer({
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) send(Frame.Text(frame.readText()))
                    }
                }
            }
        }) { port ->
            newTestHttpClient().use { client ->
                val texts = mutableListOf<String>()
                val gotTwo = CompletableFuture<List<String>>()
                val pendingText = StringBuilder()
                val ws = openWebSocket(
                    client.http,
                    port,
                    "/ws",
                    object : WebSocket.Listener {
                        override fun onOpen(webSocket: WebSocket) = webSocket.request(Long.MAX_VALUE)
                        override fun onText(
                            webSocket: WebSocket,
                            data: CharSequence,
                            last: Boolean,
                        ): CompletionStage<*>? {
                            pendingText.append(data)
                            if (last) {
                                texts += pendingText.toString()
                                pendingText.clear()
                                if (texts.size == 2) gotTwo.complete(texts.toList())
                            }
                            return null
                        }
                    },
                )
                ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
                ws.sendText("世界", true).get(5, TimeUnit.SECONDS)
                // Wait for both echoes before sending CLOSE so the server's CLOSE
                // response never races ahead of the echo frames.
                val received = gotTwo.get(5, TimeUnit.SECONDS)
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)

                assertEquals(listOf("hello", "世界"), received)
            }
        }
    }

    @Test
    fun echoBinary() {
        withKeelCioServer({
            install(WebSockets)
            routing {
                webSocket("/ws-bin") {
                    for (frame in incoming) {
                        if (frame is Frame.Binary) send(Frame.Binary(true, frame.data))
                    }
                }
            }
        }) { port ->
            newTestHttpClient().use { client ->
                val payload = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
                val gotEcho = CompletableFuture<ByteArray>()
                val ws = openWebSocket(
                    client.http,
                    port,
                    "/ws-bin",
                    object : WebSocket.Listener {
                        override fun onOpen(webSocket: WebSocket) = webSocket.request(Long.MAX_VALUE)
                        override fun onBinary(
                            webSocket: WebSocket,
                            data: ByteBuffer,
                            last: Boolean,
                        ): CompletionStage<*>? {
                            if (last) {
                                val bytes = ByteArray(data.remaining())
                                data.get(bytes)
                                gotEcho.complete(bytes)
                            }
                            return null
                        }
                    },
                )
                ws.sendBinary(ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS)
                val received = gotEcho.get(5, TimeUnit.SECONDS)
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)

                assertTrue(payload.contentEquals(received))
            }
        }
    }

    @Test
    fun coexistsWithHttpRoutes() {
        withKeelCioServer({
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    for (frame in incoming) {
                        if (frame is Frame.Text) send(Frame.Text(frame.readText()))
                    }
                }
                get("/health") { call.respondText("OK") }
            }
        }) { port ->
            // HTTP side still works.
            val conn = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertEquals("OK", conn.inputStream.bufferedReader().readText())
            conn.disconnect()

            // WS side still works.
            newTestHttpClient().use { client ->
                val gotEcho = CompletableFuture<String>()
                val pendingText = StringBuilder()
                val ws = openWebSocket(
                    client.http,
                    port,
                    "/ws",
                    object : WebSocket.Listener {
                        override fun onOpen(webSocket: WebSocket) = webSocket.request(Long.MAX_VALUE)
                        override fun onText(
                            webSocket: WebSocket,
                            data: CharSequence,
                            last: Boolean,
                        ): CompletionStage<*>? {
                            pendingText.append(data)
                            if (last) {
                                gotEcho.complete(pendingText.toString())
                                pendingText.clear()
                            }
                            return null
                        }
                    },
                )
                ws.sendText("ping", true).get(5, TimeUnit.SECONDS)
                val received = gotEcho.get(5, TimeUnit.SECONDS)
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)
                assertEquals("ping", received)
            }
        }
    }

    /**
     * Opens a WebSocket using the supplied [client]. The [client]'s lifecycle is owned
     * by the caller (typically `newTestHttpClient().use { ... }`) — this method does
     * not capture or close the client. Per-test `TestHttpClient` ownership ensures the
     * underlying selector + executor threads are torn down deterministically.
     */
    private fun openWebSocket(
        client: HttpClient,
        port: Int,
        path: String,
        listener: WebSocket.Listener,
    ): WebSocket = client.newWebSocketBuilder()
        .buildAsync(URI("ws://127.0.0.1:$port$path"), listener)
        .get(5, TimeUnit.SECONDS)

    private fun withKeelCioServer(
        module: suspend Application.() -> Unit,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(KeelCio, port = 0, module = module)
        val cfg = server.engine.configuration
        cfg.engine = NioEngine()
        cfg.keepAlive = true
        server.start(wait = false)
        try {
            val port = runBlocking {
                withTimeout(15.seconds) {
                    server.engine.resolvedConnectors().first().port
                }
            }
            block(port)
        } finally {
            server.stop(SHUTDOWN_GRACE_MS, SHUTDOWN_TIMEOUT_MS)
        }
    }

    private companion object {
        private const val SHUTDOWN_GRACE_MS = 500L
        private const val SHUTDOWN_TIMEOUT_MS = 1000L
    }
}
