package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.websocket.computeAcceptKey
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.server.websocket.dsl.webSockets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.DataInputStream
import java.io.InputStream
import java.net.Socket
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import java.net.InetSocketAddress as JavaInetSocketAddress

/**
 * M4 regression: [WsSessionImpl.send] must serialise concurrent callers so
 * the shared [WsPermessageDeflate] encoder is not driven from two coroutines
 * at once. RFC 7692 §7.2.1 makes the encoder stateful (the LZ77 window is
 * shared across messages when context-takeover is on, and even with
 * `no_context_takeover` each `compress()` call advances `Z_SYNC_FLUSH`
 * internal state). Concurrent `compress()` produces a corrupt DEFLATE
 * stream that a conformant peer rejects with `Z_DATA_ERROR`.
 *
 * Red-Green pin: a handler that fans out 50 concurrent `send()` calls on
 * `Dispatchers.Default` (forcing actual parallelism, not cooperative
 * suspension) under permessage-deflate. Before the fix the client's
 * `Inflater` throws `java.util.zip.DataFormatException` on the corrupted
 * bytes; after the fix all 50 frames inflate cleanly to their distinct
 * `msg-NN` payloads.
 *
 * The test exercises the full JVM path (NIO engine + real DEFLATE codec
 * + Inflater round-trip), so it doubles as integration coverage for the
 * upgrade-time deflate negotiation and the gather-write frame encoder.
 *
 * **Why jvmTest, not commonTest**: the test needs a multi-threaded
 * dispatcher to actually force overlapping `compress()` entries
 * (`Dispatchers.Default` on JVM), a real engine to round-trip frames
 * (`NioEngine`), a raw TCP client to read wire bytes (`java.net.Socket`),
 * and an Inflater that surfaces `DataFormatException` on corrupted
 * streams (`java.util.zip.Inflater`). None of these have a portable
 * commonMain analogue today.
 */
class WsSessionConcurrentSendTest {

    @Test
    fun `concurrent send under permessage-deflate produces independently inflatable frames`() = runBlocking {
        withTimeout(15.seconds) {
            val burstSize = 50
            val engine = NioEngine()
            val server = keelHttpServer(engine) {
                connector {
                    host = "127.0.0.1"
                    port = 0
                }
                webSockets(DeflateCodec) {
                    // threshold = 0 forces compression of every payload so
                    // each frame exercises the encoder path under contention.
                    deflate { threshold = 0 }
                    webSocket("/burst") {
                        // Fan out `burstSize` sends concurrently on a
                        // multi-thread dispatcher so they actually overlap
                        // — pre-fix the unsynchronised compress() calls
                        // corrupt the wire stream.
                        coroutineScope {
                            repeat(burstSize) { i ->
                                launch(Dispatchers.Default) { send("msg-$i") }
                            }
                        }
                        for (message in incoming) { /* drain peer CLOSE */ }
                    }
                }
            }
            server.start()
            val port = (server.localAddress as InetSocketAddress).port
            try {
                Socket().use { sock ->
                    sock.connect(JavaInetSocketAddress("127.0.0.1", port))
                    val out = sock.getOutputStream()
                    val inp = DataInputStream(sock.getInputStream())

                    val key = "dGhlIHNhbXBsZSBub25jZQ=="
                    out.write(
                        (
                            "GET /burst HTTP/1.1\r\n" +
                                "Host: localhost\r\n" +
                                "Upgrade: websocket\r\n" +
                                "Connection: Upgrade\r\n" +
                                "Sec-WebSocket-Key: $key\r\n" +
                                "Sec-WebSocket-Version: 13\r\n" +
                                "Sec-WebSocket-Extensions: permessage-deflate\r\n\r\n"
                            ).encodeToByteArray(),
                    )
                    out.flush()

                    val response = readHttpResponse(inp)
                    assertTrue(response.startsWith("HTTP/1.1 101"), "expected 101: $response")
                    assertTrue(
                        response.contains("Sec-WebSocket-Accept: ${computeAcceptKey(key)}"),
                        "accept-key mismatch: $response",
                    )
                    assertTrue(
                        response.contains("Sec-WebSocket-Extensions: permessage-deflate", ignoreCase = true),
                        "expected deflate to be negotiated: $response",
                    )

                    // Read `burstSize` server frames. Each one's RSV1 bit
                    // is set (compressed). Inflate every payload — pre-fix
                    // at least one stream is corrupted by concurrent
                    // compress() and the Inflater raises DataFormatException.
                    val seen = HashSet<String>()
                    repeat(burstSize) { idx ->
                        val payload = readServerFrameInflated(inp, idx)
                        seen.add(payload)
                    }
                    assertEquals(burstSize, seen.size, "every send must produce a distinct decoded payload")
                    repeat(burstSize) { i -> assertTrue("msg-$i" in seen, "missing msg-$i in decoded set") }

                    // Masked CLOSE frame so the server cleans up.
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

    /**
     * Reads one server-emitted compressed frame off [inp] and returns its
     * inflated payload. Throws a descriptive [AssertionError] if the
     * payload fails to inflate — that is the M4-bug surface.
     */
    private fun readServerFrameInflated(inp: DataInputStream, idx: Int): String {
        val b0 = inp.readUnsignedByte()
        val fin = (b0 and 0x80) != 0
        val rsv1 = (b0 and 0x40) != 0
        val opcode = b0 and 0x0F
        assertTrue(fin, "frame $idx must be FIN (single-frame message)")
        assertTrue(rsv1, "frame $idx must have RSV1 (permessage-deflate)")
        assertEquals(0x1, opcode, "frame $idx must be TEXT, got opcode=$opcode")

        val b1 = inp.readUnsignedByte()
        assertEquals(0, b1 and 0x80, "server frame $idx must be unmasked (RFC 6455 §5.3)")
        val len = when (val short = b1 and 0x7F) {
            126 -> inp.readUnsignedShort()
            127 -> {
                val l = inp.readLong()
                if (l > Int.MAX_VALUE) fail("frame $idx absurd length $l")
                l.toInt()
            }
            else -> short
        }
        val compressed = ByteArray(len)
        inp.readFully(compressed)

        // RFC 7692 §7.2.2: per-message DEFLATE strips the trailing
        // 00 00 FF FF Z_SYNC_FLUSH marker, so re-append it before
        // inflating in raw mode.
        val tailed = ByteArray(compressed.size + 4)
        compressed.copyInto(tailed, 0, 0, compressed.size)
        tailed[compressed.size] = 0x00
        tailed[compressed.size + 1] = 0x00
        tailed[compressed.size + 2] = 0xFF.toByte()
        tailed[compressed.size + 3] = 0xFF.toByte()

        val inflater = Inflater(true) // raw (no zlib header)
        return try {
            inflater.setInput(tailed)
            val out = ByteArray(256)
            val n = inflater.inflate(out)
            String(out, 0, n)
        } catch (e: Exception) {
            fail(
                "frame $idx failed to inflate — concurrent compress() corrupted the DEFLATE stream " +
                    "(M4 regression): ${e.message}",
            )
        } finally {
            inflater.end()
        }
    }

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
