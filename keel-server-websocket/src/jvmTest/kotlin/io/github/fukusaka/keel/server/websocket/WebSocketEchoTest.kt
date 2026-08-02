package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.computeAcceptKey
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.server.websocket.dsl.webSockets
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.InputStream
import java.net.Socket
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import java.net.InetSocketAddress as JavaInetSocketAddress

/**
 * Real-engine integration test for the `keelHttpServer { webSockets { } }`
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
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                webSockets {
                    webSocket("/echo") {
                        for (message in incoming) send(message)
                    }
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
    fun `webSocket route echoes a masked binary frame through the pooled receive path`() = runBlocking {
        withTimeout(10.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                webSockets {
                    // A binary message arrives as WsMessage.BinaryChunks (the
                    // decoder's pooled fast path + the aggregator's zero-copy
                    // assembly). onMessage echoes it back via send(it), which
                    // gather-writes and releases the pooled chunks — exercising
                    // the scoped-receive auto-release-suppressed-on-send path.
                    webSocket("/echo") {
                        onMessage { send(it) }
                    }
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

                    // Masked client BINARY frame (opcode 0x2, FIN set → 0x82),
                    // payload chosen to exercise every mask-byte phase plus the
                    // high-bit bytes the XOR must preserve.
                    val mask = byteArrayOf(0x21, 0x43, 0x65, 0x77)
                    val payload = byteArrayOf(0x00, 0x10, 0x7F, 0x80.toByte(), 0xFF.toByte(), 0x42)
                    writeMaskedFrame(out, opcodeByte = 0x82.toByte(), mask, payload)
                    out.flush()

                    // Server echoes an unmasked BINARY frame.
                    assertEquals(0x82, inp.read(), "echoed frame must be FIN + BINARY")
                    assertEquals(payload.size, inp.read(), "echoed frame must be unmasked, len = ${payload.size}")
                    assertContentEquals(payload, readFully(inp, payload.size))

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
    fun `a webSocket inside a route group inherits the prefix and group middleware`() = runBlocking {
        withTimeout(10.seconds) {
            val events = mutableListOf<String>()
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                route("/api/v1") {
                    install { _, next ->
                        events.add("group-mw")
                        next()
                    }
                    webSockets {
                        webSocket("/echo") {
                            for (message in incoming) send(message)
                        }
                    }
                }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            try {
                Socket().use { sock ->
                    sock.connect(JavaInetSocketAddress("127.0.0.1", port))
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()

                    // The endpoint resolves at the group-composed prefix.
                    val key = "dGhlIHNhbXBsZSBub25jZQ=="
                    out.write(
                        (
                            "GET /api/v1/echo HTTP/1.1\r\n" +
                                "Host: localhost\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Key: $key\r\n" +
                                "Sec-WebSocket-Version: 13\r\n\r\n"
                            ).encodeToByteArray(),
                    )
                    out.flush()

                    val response = readHttpResponse(inp)
                    assertTrue(response.startsWith("HTTP/1.1 101"), "expected 101 at /api/v1/echo: $response")
                    // The group middleware ran before the handshake completed.
                    assertEquals(listOf("group-mw"), events, "group middleware must wrap the upgrade")

                    val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                    val payload = "hi".encodeToByteArray()
                    out.write(byteArrayOf(0x81.toByte(), (0x80 or payload.size).toByte()))
                    out.write(mask)
                    out.write(ByteArray(payload.size) { (payload[it].toInt() xor mask[it % 4].toInt()).toByte() })
                    out.flush()

                    assertEquals(0x81, inp.read(), "echoed frame must be FIN + TEXT")
                    assertEquals(payload.size, inp.read())
                    val echoed = ByteArray(payload.size)
                    var read = 0
                    while (read < echoed.size) {
                        val n = inp.read(echoed, read, echoed.size - read)
                        check(n >= 0) { "stream closed before the echoed payload" }
                        read += n
                    }
                    assertEquals("hi", echoed.decodeToString())

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
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                webSockets {
                    webSocket("/chat/:room") {
                        // Send the captured :room path parameter, then drain
                        // inbound messages until the peer's CLOSE.
                        send(WsFrame.text(pathParameters["room"] ?: "(none)"))
                        for (message in incoming) { /* drain */ }
                    }
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
    fun `a constrained webSocket route matches a numeric segment and rejects others`() = runBlocking {
        withTimeout(10.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                webSockets {
                    webSocket("/chat/:room(int)") {
                        // Reached only when the :room(int) constraint passed;
                        // echo the captured value, then drain until CLOSE.
                        send(WsFrame.text(pathParameters["room"] ?: "(none)"))
                        for (message in incoming) { /* drain */ }
                    }
                }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            val key = "dGhlIHNhbXBsZSBub25jZQ=="
            try {
                // Numeric segment: the int constraint passes — the route
                // upgrades and binds :room.
                Socket().use { sock ->
                    sock.connect(JavaInetSocketAddress("127.0.0.1", port))
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()
                    out.write(
                        (
                            "GET /chat/42 HTTP/1.1\r\n" +
                                "Host: localhost\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Key: $key\r\n" +
                                "Sec-WebSocket-Version: 13\r\n\r\n"
                            ).encodeToByteArray(),
                    )
                    out.flush()

                    val response = readHttpResponse(inp)
                    assertTrue(response.startsWith("HTTP/1.1 101"), "expected 101 for /chat/42: $response")

                    assertEquals(0x81, inp.read(), "frame must be FIN + TEXT")
                    val len = inp.read()
                    val payload = ByteArray(len)
                    var read = 0
                    while (read < payload.size) {
                        val n = inp.read(payload, read, payload.size - read)
                        check(n >= 0) { "stream closed before the payload" }
                        read += n
                    }
                    assertEquals("42", payload.decodeToString())

                    val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                    out.write(byteArrayOf(0x88.toByte(), 0x80.toByte()))
                    out.write(mask)
                    out.flush()
                }

                // Non-numeric segment: the int constraint fails, so the
                // upgrade route does not match and the server answers a
                // non-101 (404) response rather than switching protocols.
                Socket().use { sock ->
                    sock.connect(JavaInetSocketAddress("127.0.0.1", port))
                    val out = sock.getOutputStream()
                    val inp = sock.getInputStream()
                    out.write(
                        (
                            "GET /chat/lobby HTTP/1.1\r\n" +
                                "Host: localhost\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Key: $key\r\n" +
                                "Sec-WebSocket-Version: 13\r\n\r\n"
                            ).encodeToByteArray(),
                    )
                    out.flush()

                    val response = readHttpResponse(inp)
                    assertTrue(
                        !response.startsWith("HTTP/1.1 101"),
                        "/chat/lobby must NOT upgrade — the int constraint rejects it: $response",
                    )
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
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                webSockets {
                    webSocket("/echo") {
                        for (message in incoming) send(message)
                    }
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
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                webSockets {
                    webSocket("/echo") {
                        for (message in incoming) send(message)
                    }
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

    @Test
    fun `webSocket route negotiates permessage-deflate and echoes a compressible message`() = runBlocking {
        withTimeout(10.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                webSockets(DeflateCodec) {
                    deflate { threshold = 0 }
                    webSocket("/echo") {
                        for (message in incoming) send(message)
                    }
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
                    // Handshake offering permessage-deflate.
                    out.write(
                        (
                            "GET /echo HTTP/1.1\r\n" +
                                "Host: localhost\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Key: $key\r\n" +
                                "Sec-WebSocket-Extensions: permessage-deflate\r\n" +
                                "Sec-WebSocket-Version: 13\r\n\r\n"
                            ).encodeToByteArray(),
                    )
                    out.flush()

                    val response = readHttpResponse(inp)
                    assertTrue(response.startsWith("HTTP/1.1 101"), "expected 101: $response")
                    assertTrue(
                        response.contains("Sec-WebSocket-Extensions: permessage-deflate"),
                        "server must accept permessage-deflate: $response",
                    )

                    // Compress a repetitive (highly compressible) message.
                    val text = "compress me ".repeat(64)
                    val compressed = rawDeflate(text.encodeToByteArray())
                    val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                    // TEXT frame with RSV1=1: byte0 = 0xC1.
                    writeMaskedFrame(out, opcodeByte = 0xC1.toByte(), mask, compressed)
                    out.flush()

                    // Server echoes a compressed (RSV1=1) TEXT frame.
                    assertEquals(0xC1, inp.read(), "echoed frame must be FIN + RSV1 + TEXT")
                    val len = inp.read()
                    assertTrue(len in 1..125, "echoed compressed payload length")
                    val echoedCompressed = readFully(inp, len)
                    assertEquals(text, rawInflate(echoedCompressed).decodeToString())

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
    fun `webSocket route sends a below-threshold message uncompressed`() = runBlocking {
        withTimeout(10.seconds) {
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                webSockets(DeflateCodec) {
                    deflate { threshold = 1024 }
                    webSocket("/echo") {
                        for (message in incoming) send(message)
                    }
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
                            "GET /echo HTTP/1.1\r\n" +
                                "Host: localhost\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Key: $key\r\n" +
                                "Sec-WebSocket-Extensions: permessage-deflate\r\n" +
                                "Sec-WebSocket-Version: 13\r\n\r\n"
                            ).encodeToByteArray(),
                    )
                    out.flush()
                    assertTrue(readHttpResponse(inp).startsWith("HTTP/1.1 101"))

                    // A short (uncompressed, RSV1=0) TEXT message "hi".
                    val mask = byteArrayOf(0x12, 0x34, 0x56, 0x78)
                    writeMaskedFrame(out, opcodeByte = 0x81.toByte(), mask, "hi".encodeToByteArray())
                    out.flush()

                    // Server's echo is below the 1 KiB threshold → RSV1=0.
                    assertEquals(0x81, inp.read(), "below-threshold echo must be uncompressed (RSV1=0)")
                    val len = inp.read()
                    assertEquals(2, len, "uncompressed payload length")
                    assertEquals("hi", readFully(inp, len).decodeToString())

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

    /**
     * Raw-DEFLATE compresses [data] with the `Z_SYNC_FLUSH` tail removed —
     * the on-wire form of a `permessage-deflate` compressed message
     * (RFC 7692 §7.2.1).
     */
    private fun rawDeflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ArrayList<Byte>()
        val buf = ByteArray(256)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            for (i in 0 until n) out.add(buf[i])
        }
        deflater.end()
        // Drop the trailing 00 00 FF FF emitted by finish() so the bytes
        // match what an RFC 7692 sender puts on the wire.
        val full = ByteArray(out.size) { out[it] }
        return if (full.size >= 4 &&
            full[full.size - 1] == 0xFF.toByte() && full[full.size - 2] == 0xFF.toByte() &&
            full[full.size - 3] == 0x00.toByte() && full[full.size - 4] == 0x00.toByte()
        ) {
            full.copyOf(full.size - 4)
        } else {
            full
        }
    }

    /** Raw-DEFLATE inflates [data], re-appending the `00 00 FF FF` sync tail. */
    private fun rawInflate(data: ByteArray): ByteArray {
        val inflater = Inflater(true)
        inflater.setInput(data + byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()))
        val out = ArrayList<Byte>()
        val buf = ByteArray(256)
        while (true) {
            val n = inflater.inflate(buf)
            if (n == 0) break
            for (i in 0 until n) out.add(buf[i])
        }
        inflater.end()
        return ByteArray(out.size) { out[it] }
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
