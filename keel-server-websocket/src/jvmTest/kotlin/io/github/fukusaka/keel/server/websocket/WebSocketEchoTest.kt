package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.websocket.computeAcceptKey
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.keelHttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.net.Socket
import java.net.InetSocketAddress as JavaInetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Real-engine integration test for the `keelHttpServer { webSocket(...) }`
 * DSL: a [NioEngine]-backed server with an echo WebSocket route, driven
 * by a raw TCP client that performs the RFC 6455 handshake and exchanges
 * masked frames.
 *
 * The shared session core ([runWebSocketUpgrade] / [WsSession]) is also
 * covered end-to-end by `KeelWebSocketTest` in `:keel-server-ktor`; this
 * pins the `keel-server-http` `WebSocketUpgrade` glue and the `webSocket`
 * DSL.
 */
class WebSocketEchoTest {

    @Test
    fun `webSocket route echoes a masked text frame`() = runBlocking {
        withTimeout(10.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                host = "127.0.0.1"
                port = 0
                webSocket("/echo") {
                    for (frame in incoming) send(frame)
                }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            try {
                Socket().use { sock ->
                    sock.connect(JavaInetSocketAddress("127.0.0.1", port))
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()

                    // RFC 6455 handshake.
                    val key = "dGhlIHNhbXBsZSBub25jZQ=="
                    out.write(
                        (
                            "GET /echo HTTP/1.1\r\n" +
                                "Host: localhost\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Key: $key\r\n" +
                                "Sec-WebSocket-Version: 13\r\n\r\n"
                            ).encodeToByteArray(),
                    )
                    out.flush()

                    val response = readHttpResponse(inp)
                    assertTrue(response.startsWith("HTTP/1.1 101"), "expected 101: $response")
                    assertTrue(
                        response.contains("Sec-WebSocket-Accept: ${computeAcceptKey(key)}"),
                        "Sec-WebSocket-Accept mismatch: $response",
                    )

                    // Masked client TEXT frame "hi" (RFC 6455 §5.2/§5.3).
                    val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                    val payload = "hi".encodeToByteArray()
                    out.write(byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()))
                    out.write(mask)
                    out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
                    out.flush()

                    // Server echoes an unmasked TEXT frame.
                    assertEquals(0x81, inp.read(), "echoed frame must be FIN + TEXT")
                    assertEquals(payload.size, inp.read(), "echoed frame must be unmasked, len = ${payload.size}")
                    val echoed = ByteArray(payload.size)
                    var read = 0
                    while (read < echoed.size) {
                        val n = inp.read(echoed, read, echoed.size - read)
                        check(n >= 0) { "stream closed before the echoed payload" }
                        read += n
                    }
                    assertEquals("hi", echoed.decodeToString())

                    // Masked CLOSE frame ends the session.
                    out.write(byteArrayOf(0x88.toByte(), 0x80.toByte()))
                    out.write(mask)
                    out.flush()
                }
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                engine.close()
            }
        }
    }

    /** Reads a raw HTTP response head, up to and including the `\r\n\r\n` terminator. */
    private fun readHttpResponse(inp: InputStream): String {
        val sb = StringBuilder()
        while (!sb.endsWith("\r\n\r\n")) {
            val b = inp.read()
            if (b < 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }
}
