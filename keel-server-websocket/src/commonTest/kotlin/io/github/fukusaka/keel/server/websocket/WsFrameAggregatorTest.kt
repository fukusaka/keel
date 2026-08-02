package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Seam-style unit tests for [WsFrameAggregator] — the pure RFC 6455 §5.4
 * fragment-reassembly state machine. Frames are fed directly; no I/O,
 * no coroutines, so no timeouts are required.
 */
class WsFrameAggregatorTest {

    @Test
    fun `an unfragmented text frame completes a single text message`() {
        val result = WsFrameAggregator().feed(WsFrame.text("hello"))
        val completed = assertIs<WsAggregateResult.Completed>(result)
        assertEquals(WsMessage.Text("hello"), completed.message)
    }

    @Test
    fun `an unfragmented binary frame completes a single binary message`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val result = WsFrameAggregator().feed(WsFrame.binary(bytes))
        val completed = assertIs<WsAggregateResult.Completed>(result)
        val message = assertIs<WsMessage.Binary>(completed.message)
        assertTrue(bytes.contentEquals(message.bytes))
    }

    @Test
    fun `an empty text message is delivered as an empty string`() {
        val result = WsFrameAggregator().feed(WsFrame.text(""))
        val completed = assertIs<WsAggregateResult.Completed>(result)
        assertEquals(WsMessage.Text(""), completed.message)
    }

    @Test
    fun `a fragmented text message across continuations is reassembled into one message`() {
        val aggregator = WsFrameAggregator()
        assertIs<WsAggregateResult.Incomplete>(
            aggregator.feed(WsFrame.text("he", fin = false)),
        )
        assertIs<WsAggregateResult.Incomplete>(
            aggregator.feed(WsFrame.continuation("ll".encodeToByteArray(), fin = false)),
        )
        val result = aggregator.feed(WsFrame.continuation("o".encodeToByteArray(), fin = true))
        val completed = assertIs<WsAggregateResult.Completed>(result)
        assertEquals(WsMessage.Text("hello"), completed.message)
    }

    @Test
    fun `a fragmented binary message is reassembled into one binary message`() {
        val aggregator = WsFrameAggregator()
        assertIs<WsAggregateResult.Incomplete>(
            aggregator.feed(WsFrame.binary(byteArrayOf(1, 2), fin = false)),
        )
        val result = aggregator.feed(WsFrame.continuation(byteArrayOf(3, 4), fin = true))
        val completed = assertIs<WsAggregateResult.Completed>(result)
        val message = assertIs<WsMessage.Binary>(completed.message)
        assertTrue(byteArrayOf(1, 2, 3, 4).contentEquals(message.bytes))
    }

    @Test
    fun `a message split into many continuation fragments is reassembled in order`() {
        // The 1 MiB-as-256x4-KiB fragmentation that large WebSocket sends
        // produce on the wire. Exercises joinChunks' offset arithmetic across
        // many chunks — the single O(n) join that replaced the former
        // per-frame O(n^2) accumulation — and confirms byte-exact, in-order
        // assembly. Each fragment is filled with its own index so a
        // misordered or misaligned join is caught at every chunk boundary.
        val fragmentCount = 256
        val fragmentSize = 4096
        val aggregator = WsFrameAggregator()
        repeat(fragmentCount - 1) { i ->
            val chunk = ByteArray(fragmentSize) { i.toByte() }
            val result = if (i == 0) {
                aggregator.feed(WsFrame.binary(chunk, fin = false))
            } else {
                aggregator.feed(WsFrame.continuation(chunk, fin = false))
            }
            assertIs<WsAggregateResult.Incomplete>(result)
        }
        val last = ByteArray(fragmentSize) { (fragmentCount - 1).toByte() }
        val completed = assertIs<WsAggregateResult.Completed>(
            aggregator.feed(WsFrame.continuation(last, fin = true)),
        )
        val bytes = assertIs<WsMessage.Binary>(completed.message).bytes
        assertEquals(fragmentCount * fragmentSize, bytes.size)
        for (f in 0 until fragmentCount) {
            assertEquals(f.toByte(), bytes[f * fragmentSize], "fragment $f start byte")
            assertEquals(f.toByte(), bytes[f * fragmentSize + fragmentSize - 1], "fragment $f end byte")
        }
    }

    @Test
    fun `the aggregator is reusable for a second message after one completes`() {
        val aggregator = WsFrameAggregator()
        assertIs<WsAggregateResult.Completed>(aggregator.feed(WsFrame.text("first")))
        val result = aggregator.feed(WsFrame.text("second"))
        val completed = assertIs<WsAggregateResult.Completed>(result)
        assertEquals(WsMessage.Text("second"), completed.message)
    }

    @Test
    fun `an orphan continuation frame with no message in progress is a protocol error`() {
        val result = WsFrameAggregator().feed(WsFrame.continuation("x".encodeToByteArray(), fin = true))
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.PROTOCOL_ERROR_CODE, error.closeCode)
    }

    @Test
    fun `a new text frame while a message is unfinished is a protocol error`() {
        val aggregator = WsFrameAggregator()
        assertIs<WsAggregateResult.Incomplete>(aggregator.feed(WsFrame.text("he", fin = false)))
        val result = aggregator.feed(WsFrame.text("new"))
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.PROTOCOL_ERROR_CODE, error.closeCode)
    }

    @Test
    fun `a new binary frame while a text message is unfinished is a protocol error`() {
        val aggregator = WsFrameAggregator()
        assertIs<WsAggregateResult.Incomplete>(aggregator.feed(WsFrame.text("he", fin = false)))
        val result = aggregator.feed(WsFrame.binary(byteArrayOf(1, 2)))
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.PROTOCOL_ERROR_CODE, error.closeCode)
    }

    @Test
    fun `an invalid UTF-8 text message is a protocol error with code 1007`() {
        val result = WsFrameAggregator().feed(
            WsFrame(fin = true, opcode = WsOpcode.TEXT, payload = byteArrayOf(0xFF.toByte())),
        )
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.INVALID_PAYLOAD_CODE, error.closeCode)
    }

    @Test
    fun `a single frame at exactly the message size limit completes`() {
        val payload = ByteArray(MAX_WS_MESSAGE_SIZE)
        val result = WsFrameAggregator().feed(WsFrame.binary(payload))
        val completed = assertIs<WsAggregateResult.Completed>(result)
        val message = assertIs<WsMessage.Binary>(completed.message)
        assertEquals(MAX_WS_MESSAGE_SIZE, message.bytes.size)
    }

    @Test
    fun `a single frame one byte over the message size limit is message too big`() {
        val payload = ByteArray(MAX_WS_MESSAGE_SIZE + 1)
        val result = WsFrameAggregator().feed(WsFrame.binary(payload, fin = false))
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.MESSAGE_TOO_BIG_CODE, error.closeCode)
    }

    @Test
    fun `an unfragmented fin frame over the message size limit is message too big`() {
        val payload = ByteArray(MAX_WS_MESSAGE_SIZE + 1)
        val result = WsFrameAggregator().feed(WsFrame.binary(payload))
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.MESSAGE_TOO_BIG_CODE, error.closeCode)
    }

    @Test
    fun `accumulated continuations crossing the size limit are message too big`() {
        val aggregator = WsFrameAggregator()
        val half = ByteArray(MAX_WS_MESSAGE_SIZE / 2 + 1)
        assertIs<WsAggregateResult.Incomplete>(aggregator.feed(WsFrame.binary(half, fin = false)))
        val result = aggregator.feed(WsFrame.continuation(half, fin = false))
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.MESSAGE_TOO_BIG_CODE, error.closeCode)
    }

    @Test
    fun `feeding a control frame is a programming error`() {
        assertFailsWith<IllegalArgumentException> {
            WsFrameAggregator().feed(WsFrame.ping())
        }
    }

    // --- permessage-deflate (RFC 7692 §7.2) ---

    /** A `permessage-deflate` engine wired as the aggregator's inflater. */
    private fun deflateEngine(): WsPermessageDeflate =
        WsPermessageDeflate(
            allocator = DefaultAllocator,
            codec = DeflateCodec,
            options = WsDeflateOptions(contextTakeover = false, threshold = 0),
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )

    @Test
    fun `a compressed single-frame text message is inflated`() {
        val engine = deflateEngine()
        try {
            val original = "permessage-deflate ".repeat(32)
            val compressed = engine.compress(original.encodeToByteArray())
            assertTrue(compressed.compressed)
            val aggregator = WsFrameAggregator(WsMessageInflater { engine.decompress(it) })
            val result = aggregator.feed(
                WsFrame(fin = true, rsv1 = true, opcode = WsOpcode.TEXT, payload = wireBytes(compressed)),
            )
            val completed = assertIs<WsAggregateResult.Completed>(result)
            assertEquals(WsMessage.Text(original), completed.message)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a compressed fragmented binary message is inflated`() {
        val engine = deflateEngine()
        try {
            val original = ByteArray(2048) { (it and 0x3F).toByte() }
            val compressed = wireBytes(engine.compress(original))
            // Split the compressed bytes across an opening frame (rsv1=1)
            // and a CONTINUATION frame (rsv1=0, fin=1).
            val mid = compressed.size / 2
            val aggregator = WsFrameAggregator(WsMessageInflater { engine.decompress(it) })
            assertIs<WsAggregateResult.Incomplete>(
                aggregator.feed(
                    WsFrame(
                        fin = false,
                        rsv1 = true,
                        opcode = WsOpcode.BINARY,
                        payload = compressed.copyOfRange(0, mid),
                    ),
                ),
            )
            val result = aggregator.feed(
                WsFrame.continuation(compressed.copyOfRange(mid, compressed.size), fin = true),
            )
            val completed = assertIs<WsAggregateResult.Completed>(result)
            val message = assertIs<WsMessage.Binary>(completed.message)
            assertTrue(original.contentEquals(message.bytes))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `an rsv1 frame with no inflater configured is a protocol error 1002`() {
        val result = WsFrameAggregator().feed(
            WsFrame(fin = true, rsv1 = true, opcode = WsOpcode.TEXT, payload = byteArrayOf(1, 2, 3)),
        )
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.PROTOCOL_ERROR_CODE, error.closeCode)
    }

    @Test
    fun `a decompressed message over the size cap is message too big 1009`() {
        // A test inflater that expands any input past MAX_WS_MESSAGE_SIZE.
        val bombInflater = WsMessageInflater { ByteArray(MAX_WS_MESSAGE_SIZE + 1) }
        val aggregator = WsFrameAggregator(bombInflater)
        val result = aggregator.feed(
            WsFrame(fin = true, rsv1 = true, opcode = WsOpcode.BINARY, payload = byteArrayOf(0x42)),
        )
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.MESSAGE_TOO_BIG_CODE, error.closeCode)
    }

    @Test
    fun `a decompressed text message with invalid UTF-8 is a protocol error 1007`() {
        // The inflater yields a lone 0xFF byte — not valid UTF-8.
        val badUtf8Inflater = WsMessageInflater { byteArrayOf(0xFF.toByte()) }
        val aggregator = WsFrameAggregator(badUtf8Inflater)
        val result = aggregator.feed(
            WsFrame(fin = true, rsv1 = true, opcode = WsOpcode.TEXT, payload = byteArrayOf(0x01)),
        )
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.INVALID_PAYLOAD_CODE, error.closeCode)
    }

    @Test
    fun `a decompression cap firing is message too big 1009 not protocol error 1002`() {
        // An inflater that throws DecompressionLimitException — what the
        // real `WsPermessageDeflate` decoder does when its `maxOutputSize`
        // cap fires (zip-bomb scenario). RFC 6455 §7.4.1 assigns 1009
        // (MESSAGE_TOO_BIG) to this case; the prior `runCatching.getOrNull`
        // collapsed every codec throw to 1002 (PROTOCOL_ERROR) and
        // misled the peer about whether the size constraint or the framing
        // failed.
        val limitInflater = WsMessageInflater {
            throw io.github.fukusaka.keel.compression.DecompressionLimitException("test cap")
        }
        val aggregator = WsFrameAggregator(limitInflater)
        val result = aggregator.feed(
            WsFrame(fin = true, rsv1 = true, opcode = WsOpcode.BINARY, payload = byteArrayOf(0x42)),
        )
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.MESSAGE_TOO_BIG_CODE, error.closeCode)
    }

    @Test
    fun `a decompression failure is a protocol error 1002`() {
        val engine = deflateEngine()
        try {
            val aggregator = WsFrameAggregator(WsMessageInflater { engine.decompress(it) })
            // Garbage bytes that are not a valid raw-DEFLATE stream.
            val result = aggregator.feed(
                WsFrame(fin = true, rsv1 = true, opcode = WsOpcode.BINARY, payload = byteArrayOf(-1, -1, -1, -1)),
            )
            val error = assertIs<WsAggregateResult.ProtocolError>(result)
            assertEquals(WsFrameAggregator.PROTOCOL_ERROR_CODE, error.closeCode)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a malformed compressed message logs the inflate cause with the message ordinal`() {
        // M1/M2: the peer only ever sees a generic 1002 close (the wire reason
        // must not leak codec internals), so the actual cause and which message
        // failed are recorded on the connection logger for the operator.
        val log = CapturingLogger()
        val boom = RuntimeException("simulated Z_DATA_ERROR")
        val aggregator = WsFrameAggregator(
            inflater = WsMessageInflater { throw boom },
            logger = log,
        )
        val result = aggregator.feed(
            WsFrame(fin = true, rsv1 = true, opcode = WsOpcode.BINARY, payload = byteArrayOf(1, 2, 3)),
        )
        val error = assertIs<WsAggregateResult.ProtocolError>(result)
        assertEquals(WsFrameAggregator.PROTOCOL_ERROR_CODE, error.closeCode)
        // Wire reason stays generic — the cause does not leak to the peer.
        assertFalse(error.reason.contains("Z_DATA_ERROR"), "wire reason must not leak the cause: ${error.reason}")
        // Operator-facing log carries the cause (M1) and the failing ordinal (M2).
        val warn = log.entries.single { it.level == LogLevel.WARN }
        assertSame(boom, warn.throwable, "the inflate cause must be logged, not swallowed")
        assertTrue(
            warn.message?.toString()?.contains("#1") == true,
            "log must name the message ordinal: ${warn.message}",
        )
    }

    private class CapturingLogger : Logger {
        data class Entry(val level: LogLevel, val throwable: Throwable?, val message: Any?)

        val entries: MutableList<Entry> = mutableListOf()

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            entries.add(Entry(level, throwable, message))
        }
    }
}
