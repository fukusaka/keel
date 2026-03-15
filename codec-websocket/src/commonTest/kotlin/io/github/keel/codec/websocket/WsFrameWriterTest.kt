package io.github.keel.codec.websocket

import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WsFrameWriterTest {

    @Test
    fun writeUnmaskedTextFrame() {
        val buf = Buffer()
        writeFrame(WsFrame.text("hello"), buf)
        val bytes = buf.readByteArray()
        assertEquals(0x81.b, bytes[0])   // FIN + TEXT
        assertEquals(0x05, bytes[1])     // len=5, no mask
        assertContentEquals("hello".encodeToByteArray(), bytes.copyOfRange(2, 7))
    }

    @Test
    fun writeMaskedTextFrame() {
        val maskKey = 0x12345678
        val buf = Buffer()
        writeFrame(WsFrame.text("Hi", maskKey = maskKey), buf)
        val bytes = buf.readByteArray()
        assertEquals(0x81.b, bytes[0])
        assertEquals(0x82.b, bytes[1])   // MASK bit + len=2
        // masking key
        assertEquals(0x12.b, bytes[2])
        assertEquals(0x34.b, bytes[3])
        assertEquals(0x56.b, bytes[4])
        assertEquals(0x78.b, bytes[5])
        // payload must be masked
        val plain = "Hi".encodeToByteArray()
        val key = byteArrayOf(0x12, 0x34, 0x56, 0x78)
        val expectedMasked = ByteArray(plain.size) { i -> (plain[i].toInt() xor key[i % 4].toInt()).toByte() }
        assertContentEquals(expectedMasked, bytes.copyOfRange(6, 8))
    }

    @Test
    fun write16BitPayloadLength() {
        val payload = ByteArray(126)
        val buf = Buffer()
        writeFrame(WsFrame.binary(payload), buf)
        val bytes = buf.readByteArray()
        assertEquals(0x82.b, bytes[0])
        assertEquals(0x7E.b, bytes[1])   // extended payload length indicator
        assertEquals(0x00, bytes[2])
        assertEquals(0x7E.b, bytes[3])   // 126
    }

    @Test
    fun write64BitPayloadLength() {
        val payload = ByteArray(65536)
        val buf = Buffer()
        writeFrame(WsFrame.binary(payload), buf)
        val bytes = buf.readByteArray()
        assertEquals(0x82.b, bytes[0])
        assertEquals(0x7F.b, bytes[1])   // 64-bit extended payload length
        // 8-byte big-endian length = 65536 = 0x00010000
        assertEquals(0x00, bytes[2]); assertEquals(0x00, bytes[3])
        assertEquals(0x00, bytes[4]); assertEquals(0x00, bytes[5])
        assertEquals(0x00, bytes[6]); assertEquals(0x01.b, bytes[7])
        assertEquals(0x00, bytes[8]); assertEquals(0x00, bytes[9])
    }

    @Test
    fun writeFragmentedFrame() {
        val buf = Buffer()
        writeFrame(WsFrame.continuation("part".encodeToByteArray(), fin = false), buf)
        val bytes = buf.readByteArray()
        assertEquals(0x00, bytes[0])  // FIN=0 + CONTINUATION opcode
    }

    @Test
    fun writeControlFramePing() {
        val buf = Buffer()
        writeFrame(WsFrame.ping(), buf)
        val bytes = buf.readByteArray()
        assertEquals(0x89.b, bytes[0])  // FIN + PING
        assertEquals(0x00, bytes[1])    // len=0
        assertEquals(2, bytes.size)
    }

    @Test
    fun writeControlFrameClose() {
        val buf = Buffer()
        writeFrame(WsFrame.close(WsCloseCode.NORMAL_CLOSURE), buf)
        val bytes = buf.readByteArray()
        assertEquals(0x88.b, bytes[0])  // FIN + CLOSE
        assertEquals(0x02, bytes[1])    // len=2
        assertEquals(0x03.b, bytes[2])  // 1000 >> 8
        assertEquals(0xE8.b, bytes[3])  // 1000 & 0xFF
    }

    @Test
    fun maskingApplied() {
        val maskKey = 0xAABBCCDD.toInt()
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val buf = Buffer()
        writeFrame(WsFrame.binary(payload, maskKey = maskKey), buf)
        val bytes = buf.readByteArray()
        // masked payload starts at byte 6
        val keyBytes = byteArrayOf(0xAA.b, 0xBB.b, 0xCC.b, 0xDD.b)
        val expectedMasked = ByteArray(payload.size) { i -> (payload[i].toInt() xor keyBytes[i % 4].toInt()).toByte() }
        assertContentEquals(expectedMasked, bytes.copyOfRange(6, 11))
    }

    @Test
    fun roundTripMasked() {
        val original = WsFrame.text("round-trip masked", maskKey = 0xDEADBEEF.toInt())
        val buf = Buffer()
        writeFrame(original, buf)
        val parsed = parseFrame(buf)
        assertContentEquals(original.payload, parsed.payload)
    }

    @Test
    fun roundTripUnmasked() {
        val original = WsFrame.binary(byteArrayOf(10, 20, 30, 40))
        val buf = Buffer()
        writeFrame(original, buf)
        val parsed = parseFrame(buf)
        assertContentEquals(original.payload, parsed.payload)
        assertEquals(original.opcode, parsed.opcode)
    }

    @Test
    fun controlFrameOversizeThrows() {
        assertFailsWith<IllegalArgumentException> {
            val frame = WsFrame(fin = true, opcode = WsOpcode.PING, payload = ByteArray(126))
            writeFrame(frame, Buffer())
        }
    }

    // --- helpers ---

    private val Int.b: Byte get() = toByte()
}
