package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
}
