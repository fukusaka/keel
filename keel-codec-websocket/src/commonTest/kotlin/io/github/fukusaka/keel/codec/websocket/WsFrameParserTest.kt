package io.github.fukusaka.keel.codec.websocket

import kotlinx.io.Buffer
import kotlinx.io.write
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WsFrameParserTest {

    // --- parseUnmaskedTextFrame ---

    @Test
    fun parseUnmaskedTextFrame() {
        // 0x81 0x05 "hello"
        val src = buf(byteArrayOf(0x81.b, 0x05, 'h'.code.b, 'e'.code.b, 'l'.code.b, 'l'.code.b, 'o'.code.b))
        val f = parseFrame(src)
        assertTrue(f.fin)
        assertEquals(WsOpcode.TEXT, f.opcode)
        assertContentEquals("hello".encodeToByteArray(), f.payload)
        assertNull(f.maskKey)
    }

    @Test
    fun parseMaskedTextFrame() {
        // 0x81 0x85 [mask 4 bytes] [masked "Hello"]
        // mask = 0x37FA213D, "Hello" XORed
        val mask = byteArrayOf(0x37, 0xFA.b, 0x21, 0x3D)
        val plain = "Hello".encodeToByteArray()
        val masked = ByteArray(plain.size) { i -> (plain[i].toInt() xor mask[i % 4].toInt()).toByte() }
        val frame = byteArrayOf(0x81.b, 0x85.b) + mask + masked
        val f = parseFrame(buf(frame))
        assertEquals(WsOpcode.TEXT, f.opcode)
        assertContentEquals("Hello".encodeToByteArray(), f.payload)
        assertEquals(0x37FA213D, f.maskKey)
    }

    @Test
    fun parseMaskedBinaryFrameLongerThanOneMaskCycle() {
        // Pins the unmask key indexing across more than two full 4-byte
        // cycles (the in-place unmask uses `i and 3` for the key byte).
        // A 10-byte payload exercises key positions 0,1,2,3,0,1,2,3,0,1.
        val mask = byteArrayOf(0x12, 0x34, 0x56.b, 0x78)
        val plain = ByteArray(10) { (it * 7 + 3).toByte() }
        val masked = ByteArray(plain.size) { i -> (plain[i].toInt() xor mask[i % 4].toInt()).toByte() }
        val frame = byteArrayOf(0x82.b, (0x80 or plain.size).b) + mask + masked
        val f = parseFrame(buf(frame))
        assertEquals(WsOpcode.BINARY, f.opcode)
        assertContentEquals(plain, f.payload)
    }

    @Test
    fun parseUnmaskedBinaryFrame() {
        val data = byteArrayOf(0x01, 0x02, 0x03)
        val src = buf(byteArrayOf(0x82.b, 0x03) + data)
        val f = parseFrame(src)
        assertEquals(WsOpcode.BINARY, f.opcode)
        assertContentEquals(data, f.payload)
    }

    @Test
    fun parse16BitPayloadLength() {
        val payload = ByteArray(126) { it.toByte() }
        // 0x82 0x7E [len 0x00 0x7E] [payload]
        val src = buf(byteArrayOf(0x82.b, 0x7E, 0x00, 0x7E.b) + payload)
        val f = parseFrame(src)
        assertEquals(126, f.payload.size)
        assertContentEquals(payload, f.payload)
    }

    @Test
    fun parse64BitPayloadLength() {
        val payload = ByteArray(65536) { (it and 0xFF).toByte() }
        // 0x82 0x7F [8 bytes len = 65536] [payload]
        val lenBytes = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00)
        val src = buf(byteArrayOf(0x82.b, 0x7F) + lenBytes + payload)
        val f = parseFrame(src)
        assertEquals(65536, f.payload.size)
    }

    @Test
    fun parseFinFalseFragment() {
        val src = buf(byteArrayOf(0x01, 0x05) + "hello".encodeToByteArray())
        val f = parseFrame(src)
        assertFalse(f.fin)
        assertEquals(WsOpcode.TEXT, f.opcode)
    }

    @Test
    fun parseControlFramePing() {
        val src = buf(byteArrayOf(0x89.b, 0x00))
        val f = parseFrame(src)
        assertTrue(f.fin)
        assertEquals(WsOpcode.PING, f.opcode)
        assertEquals(0, f.payload.size)
    }

    @Test
    fun parseControlFrameCloseWithCode() {
        // Close with status 1000 (0x03E8)
        val src = buf(byteArrayOf(0x88.b, 0x02, 0x03, 0xE8.b))
        val f = parseFrame(src)
        assertEquals(WsOpcode.CLOSE, f.opcode)
        assertEquals(2, f.payload.size)
        val code = ((f.payload[0].toInt() and 0xFF) shl 8) or (f.payload[1].toInt() and 0xFF)
        assertEquals(1000, code)
    }

    @Test
    fun parseCloseFrameEmptyPayload() {
        val src = buf(byteArrayOf(0x88.b, 0x00))
        val f = parseFrame(src)
        assertEquals(WsOpcode.CLOSE, f.opcode)
        assertEquals(0, f.payload.size)
    }

    @Test
    fun controlFrameTooLargeThrows() {
        // PING with 126-byte payload (invalid)
        val payload = ByteArray(126)
        val src = buf(byteArrayOf(0x89.b, 0x7E, 0x00, 0x7E.b) + payload)
        assertFailsWith<IllegalArgumentException> { parseFrame(src) }
    }

    @Test
    fun controlFrameFragmentedThrows() {
        // fin=false + opcode=PING (0x09 without FIN bit)
        val src = buf(byteArrayOf(0x09, 0x00))
        assertFailsWith<IllegalArgumentException> { parseFrame(src) }
    }

    @Test
    fun unknownOpcodeThrows() {
        // opcode = 0x3 (unknown)
        val src = buf(byteArrayOf(0x83.b, 0x00))
        assertFailsWith<IllegalArgumentException> { parseFrame(src) }
    }

    @Test
    fun reservedBitsNonZeroThrows() {
        // RSV1 = 1: byte0 = 0xC1 (FIN=1, RSV1=1, opcode=TEXT)
        val src = buf(byteArrayOf(0xC1.b, 0x00))
        assertFailsWith<IllegalArgumentException> { parseFrame(src) }
    }

    @Test
    fun `rsv1 is accepted when allowRsv1 is true`() {
        // byte0 = 0xC1 (FIN=1, RSV1=1, opcode=TEXT) — permessage-deflate marker.
        val src = buf(byteArrayOf(0xC1.b, 0x03, 'a'.code.b, 'b'.code.b, 'c'.code.b))
        val f = parseFrame(src, allowRsv1 = true)
        assertTrue(f.rsv1)
        assertEquals(WsOpcode.TEXT, f.opcode)
        assertContentEquals("abc".encodeToByteArray(), f.payload)
    }

    @Test
    fun `rsv1 is still rejected when allowRsv1 is false`() {
        val src = buf(byteArrayOf(0xC1.b, 0x00))
        assertFailsWith<IllegalArgumentException> { parseFrame(src, allowRsv1 = false) }
    }

    @Test
    fun `rsv2 is rejected even when allowRsv1 is true`() {
        // byte0 = 0xA1 (FIN=1, RSV2=1, opcode=TEXT).
        val src = buf(byteArrayOf(0xA1.b, 0x00))
        assertFailsWith<IllegalArgumentException> { parseFrame(src, allowRsv1 = true) }
    }

    @Test
    fun `rsv3 is rejected even when allowRsv1 is true`() {
        // byte0 = 0x91 (FIN=1, RSV3=1, opcode=TEXT).
        val src = buf(byteArrayOf(0x91.b, 0x00))
        assertFailsWith<IllegalArgumentException> { parseFrame(src, allowRsv1 = true) }
    }

    @Test
    fun roundTripUnmasked() {
        val original = WsFrame.text("round-trip")
        val buf = Buffer()
        writeFrame(original, buf)
        val parsed = parseFrame(buf)
        assertEquals(original.fin, parsed.fin)
        assertEquals(original.opcode, parsed.opcode)
        assertContentEquals(original.payload, parsed.payload)
    }

    // --- helpers ---

    private val Int.b: Byte get() = toByte()
    private val Char.b: Byte get() = code.toByte()

    private fun buf(bytes: ByteArray): Buffer = Buffer().also { it.write(bytes) }
}
