package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.codec.websocket.WsCloseCode
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.ktor.websocket.keelWebSocket
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
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

class KeelWebSocketTest {

    // --- Echo loop happy path ---

    @Test
    fun echoText() {
        withKeelServer({
            keelWebSocket("/echo") {
                for (frame in incoming) send(frame)
            }
        }) { port ->
            val recorder = WsRecorder()
            val ws = openWebSocket(port, "/echo", recorder)
            ws.sendText("hello", true).get(5, TimeUnit.SECONDS)
            ws.sendText("世界", true).get(5, TimeUnit.SECONDS)
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye").get(5, TimeUnit.SECONDS)
            recorder.awaitClosed(5)

            assertEquals(listOf("hello", "世界"), recorder.texts)
        }
    }

    @Test
    fun echoBinary() {
        withKeelServer({
            keelWebSocket("/echo") {
                for (frame in incoming) send(frame)
            }
        }) { port ->
            val recorder = WsRecorder()
            val ws = openWebSocket(port, "/echo", recorder)
            val payload = byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte())
            ws.sendBinary(ByteBuffer.wrap(payload), true).get(5, TimeUnit.SECONDS)
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(5, TimeUnit.SECONDS)
            recorder.awaitClosed(5)

            assertEquals(1, recorder.binaries.size)
            assertTrue(payload.contentEquals(recorder.binaries[0]))
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
            val client = HttpClient.newHttpClient()
            val recorder = WsRecorder()
            val future = client.newWebSocketBuilder().buildAsync(
                URI("ws://127.0.0.1:$port/nonexistent"),
                recorder,
            )
            val ex = runCatching { future.get(5, TimeUnit.SECONDS) }.exceptionOrNull()
            assertNotNull(ex, "expected handshake to fail when path is not registered")
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
            val recorder = WsRecorder()
            openWebSocket(port, "/early-close", recorder)
            recorder.awaitClosed(5)

            assertEquals(listOf("welcome"), recorder.texts)
            assertEquals(WsCloseCode.GOING_AWAY.code, recorder.closeStatusCode)
            assertEquals("shutting down", recorder.closeReason)
        }
    }

    // --- Helpers ---

    private fun openWebSocket(port: Int, path: String, listener: WebSocket.Listener): WebSocket {
        val client = HttpClient.newHttpClient()
        return client.newWebSocketBuilder()
            .buildAsync(URI("ws://127.0.0.1:$port$path"), listener)
            .get(5, TimeUnit.SECONDS)
    }

    private fun withKeelServer(
        module: suspend Application.() -> Unit,
        block: (port: Int) -> Unit,
    ) {
        val server = embeddedServer(Keel, port = 0, module = module)
        val cfg = (server.engine as KeelApplicationEngine).configuration
        cfg.engine = NioEngine()
        cfg.keepAlive = true
        server.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
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
