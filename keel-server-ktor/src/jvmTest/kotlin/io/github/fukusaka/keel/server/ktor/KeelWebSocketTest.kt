package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.codec.websocket.WsCloseCode
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.ktor.websocket.keelWebSocket
import io.github.fukusaka.keel.testing.http.newTestHttpClient
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class KeelWebSocketTest {

    // --- Echo loop happy path ---

    @Test
    fun echoText() {
        withKeelServer({
            keelWebSocket("/echo") {
                for (message in incoming) send(message)
            }
        }) { port ->
            newTestHttpClient().use { client ->
                val recorder = WsRecorder()
                val ws = openWebSocket(client.http, port, "/echo", recorder)
                ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
                ws.sendText("世界", true).get(5, TimeUnit.SECONDS)
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS)
                recorder.awaitClosed(5)

                assertEquals(listOf("hello", "世界"), recorder.texts)
            }
        }
    }

    @Test
    fun echoBinary() {
        withKeelServer({
            keelWebSocket("/echo") {
                for (message in incoming) send(message)
            }
        }) { port ->
            newTestHttpClient().use { client ->
                val recorder = WsRecorder()
                val ws = openWebSocket(client.http, port, "/echo", recorder)
                val payload = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
                ws.sendBinary(ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS)
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)
                recorder.awaitClosed(5)

                assertEquals(1, recorder.binaries.size)
                assertTrue(payload.contentEquals(recorder.binaries[0]))
            }
        }
    }

    // --- Standard Ktor WebSocket plugin (respondUpgrade path) ---

    @Test
    fun standardWsServerInitiatedMessage() {
        // Simpler test: server sends a message immediately after upgrade.
        withKeelServer({
            install(WebSockets)
            routing {
                webSocket("/ws") {
                    send(Frame.Text("from-server"))
                    close(CloseReason(CloseReason.Codes.NORMAL, "done"))
                }
            }
        }) { port ->
            newTestHttpClient().use { client ->
                val recorder = WsRecorder()
                openWebSocket(client.http, port, "/ws", recorder)
                recorder.awaitClosed(5)
                assertEquals(listOf("from-server"), recorder.texts)
            }
        }
    }

    @Test
    fun standardWsEchoText() {
        // Server echoes each message; client waits for both echoes before
        // sending CLOSE so the server's CLOSE response never races ahead.
        withKeelServer({
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
                ws.sendText("world", true).get(5, TimeUnit.SECONDS)
                // Wait for both echoes before sending CLOSE.
                val received = gotTwo.get(5, TimeUnit.SECONDS)
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)

                assertEquals(listOf("hello", "world"), received)
            }
        }
    }

    @Test
    fun standardWsEchoBinary() {
        withKeelServer({
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
                val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
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
    fun standardWsCoexistsWithHttp() {
        withKeelServer({
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

    // --- Coexistence with regular HTTP routes ---

    @Test
    fun nonUpgradeRequestStillRoutedToKtorPipeline() {
        withKeelServer({
            keelWebSocket("/ws") {
                send(WsFrame.text("ws-served"))
                close()
            }
            routing {
                get("/health") { call.respondText("OK") }
            }
        }) { port ->
            val conn = URI("http://127.0.0.1:$port/health").toURL().openConnection() as HttpURLConnection
            assertEquals(200, conn.responseCode)
            assertEquals("OK", conn.inputStream.bufferedReader().readText())
            conn.disconnect()
        }
    }

    @Test
    fun unknownPathOnWsUpgradeFallsThroughToKtor() {
        // Upgrade request to a path with no keelWebSocket registration
        // should still go through the Ktor pipeline, which (with no
        // matching route) returns 404.
        withKeelServer({
            keelWebSocket("/ws") {
                close()
            }
            routing {
                get("/health") { call.respondText("OK") }
            }
        }) { port ->
            newTestHttpClient().use { client ->
                val recorder = WsRecorder()
                val future = client.http.newWebSocketBuilder().buildAsync(
                    URI("ws://127.0.0.1:$port/nonexistent"),
                    recorder,
                )
                val ex = runCatching { future.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
                assertNotNull(ex, "expected handshake to fail when path is not registered")
            }
        }
    }

    @Test
    fun serverInitiatedClose() {
        withKeelServer({
            keelWebSocket("/early-close") {
                send(WsFrame.text("welcome"))
                close(WsCloseCode.GOING_AWAY, "shutting down")
            }
        }) { port ->
            newTestHttpClient().use { client ->
                val recorder = WsRecorder()
                openWebSocket(client.http, port, "/early-close", recorder)
                recorder.awaitClosed(5)

                assertEquals(listOf("welcome"), recorder.texts)
                assertEquals(WsCloseCode.GOING_AWAY.code, recorder.closeStatusCode)
                assertEquals("shutting down", recorder.closeReason)
            }
        }
    }

    // --- Helpers ---

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
            server.stop(500, 1000)
        }
    }

    private class WsRecorder : WebSocket.Listener {
        val texts = mutableListOf<String>()
        val binaries = mutableListOf<ByteArray>()
        var closeStatusCode: Int = 0
        var closeReason: String = ""
        private val pendingText = StringBuilder()
        private val closed = CompletableFuture<Unit>()

        override fun onOpen(webSocket: WebSocket) {
            // Request unlimited demand up-front so the JDK WebSocket
            // delivers all received frames as they arrive instead of
            // gating on the listener's per-frame request — that gating
            // makes the test sensitive to scheduling on slower hosts.
            webSocket.request(Long.MAX_VALUE)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            pendingText.append(data)
            if (last) {
                texts += pendingText.toString()
                pendingText.clear()
            }
            return null
        }

        override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
            val bytes = ByteArray(data.remaining())
            data.get(bytes)
            binaries += bytes
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            closeStatusCode = statusCode
            closeReason = reason
            closed.complete(Unit)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            closed.completeExceptionally(error)
        }

        fun awaitClosed(seconds: Long) {
            closed.get(seconds, TimeUnit.SECONDS)
        }
    }
}
