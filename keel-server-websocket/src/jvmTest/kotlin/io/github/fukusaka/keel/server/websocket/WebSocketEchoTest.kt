package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.websocket.WsFrame
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
                connector { host = "127.0.0.1"; port = 0 }
                webSocket("/echo") {
                    for (message in incoming) send(message)
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

    @Test
    fun `webSocket route exposes path parameters to the session`() = runBlocking {
        withTimeout(10.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector { host = "127.0.0.1"; port = 0 }
                webSocket("/chat/:room") {
                    // Send the captured :room path parameter, then drain
                    // inbound messages until the peer's CLOSE.
                    send(WsFrame.text(pathParameters["room"] ?: "(none)"))
                    for (message in incoming) { /* drain */ }
                }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            try {
                Socket().use { sock ->
                    sock.connect(JavaInetSocketAddress("127.0.0.1", port))
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()

                    val key = "dGhlIHNhbXBsZSBub25jZQ=="
                    out.write(
                        (
                            "GET /chat/general HTTP/1.1\r\n" +
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

                    // Server sends the :room parameter ("general") as an
                    // unmasked TEXT frame.
                    assertEquals(0x81, inp.read(), "frame must be FIN + TEXT")
                    val len = inp.read()
                    assertEquals("general".length, len, "unmasked payload length")
                    val payload = ByteArray(len)
                    var read = 0
                    while (read < payload.size) {
                        val n = inp.read(payload, read, payload.size - read)
                        check(n >= 0) { "stream closed before the payload" }
                        read += n
                    }
                    assertEquals("general", payload.decodeToString())

                    // Masked CLOSE frame ends the session.
                    val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
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

    @Test
    fun `webSocket route reassembles a fragmented text message`() = runBlocking {
        withTimeout(10.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector { host = "127.0.0.1"; port = 0 }
                webSocket("/echo") {
                    for (message in incoming) send(message)
                }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            try {
                Socket().use { sock ->
                    sock.connect(JavaInetSocketAddress("127.0.0.1", port))
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()

                    val key = "dGhlIHNhbXBsZSBub25jZQ=="
                    out.write(handshakeRequest("/echo", key).encodeToByteArray())
                    out.flush()
                    assertTrue(readHttpResponse(inp).startsWith("HTTP/1.1 101"))

                    val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                    // TEXT fin=0 "he" (opcode 0x1, FIN bit clear).
                    writeMaskedFrame(out, opcodeByte = 0x01, mask, "he".encodeToByteArray())
                    // CONTINUATION fin=1 "llo" (opcode 0x0, FIN bit set).
                    writeMaskedFrame(out, opcodeByte = 0x80.toByte(), mask, "llo".encodeToByteArray())
                    out.flush()

                    // Server reassembles and echoes a single "hello" TEXT message.
                    assertEquals(0x81, inp.read(), "echoed frame must be FIN + TEXT")
                    val len = inp.read()
                    assertEquals(5, len, "reassembled payload length")
                    val echoed = readFully(inp, len)
                    assertEquals("hello", echoed.decodeToString())

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

    @Test
    fun `webSocket route fails an invalid UTF-8 text message with close 1007`() = runBlocking {
        withTimeout(10.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector { host = "127.0.0.1"; port = 0 }
                webSocket("/echo") {
                    for (message in incoming) send(message)
                }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            try {
                Socket().use { sock ->
                    sock.connect(JavaInetSocketAddress("127.0.0.1", port))
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()

                    val key = "dGhlIHNhbXBsZSBub25jZQ=="
                    out.write(handshakeRequest("/echo", key).encodeToByteArray())
                    out.flush()
                    assertTrue(readHttpResponse(inp).startsWith("HTTP/1.1 101"))

                    // TEXT frame whose payload (0xFF) is not valid UTF-8.
                    val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                    writeMaskedFrame(out, opcodeByte = 0x81.toByte(), mask, byteArrayOf(0xFF.toByte()))
                    out.flush()

                    // Server fails the connection with a CLOSE carrying 1007.
                    assertEquals(0x88, inp.read(), "expected a CLOSE frame")
                    val len = inp.read()
                    assertTrue(len >= 2, "CLOSE payload must carry a status code")
                    val payload = readFully(inp, len)
                    val closeCode = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
                    assertEquals(1007, closeCode, "invalid UTF-8 must close with 1007")
                }
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
                engine.close()
            }
        }
    }

    /** RFC 6455 handshake request for [path] with client nonce [key]. */
    private fun handshakeRequest(path: String, key: String): String =
        "GET $path HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n\r\n"

    /**
     * Writes a masked client frame: [opcodeByte] is the first byte
     * (FIN bit + opcode), [mask] the 4-byte masking key, [payload] the
     * unmasked data (XOR-masked on the wire per RFC 6455 §5.3).
     */
    private fun writeMaskedFrame(out: java.io.OutputStream, opcodeByte: Byte, mask: ByteArray, payload: ByteArray) {
        out.write(byteArrayOf(opcodeByte, (0x80 or payload.size).toByte()))
        out.write(mask)
        out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
    }

    /** Reads exactly [len] bytes from [inp], failing if the stream ends early. */
    private fun readFully(inp: InputStream, len: Int): ByteArray {
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = inp.read(buf, read, len - read)
            check(n >= 0) { "stream closed before $len bytes" }
            read += n
        }
        return buf
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
