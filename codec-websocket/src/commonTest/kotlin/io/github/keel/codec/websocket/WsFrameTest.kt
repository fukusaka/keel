package io.github.keel.codec.websocket

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WsFrameTest {

    @Test
    fun textFrameFactory() {
        val f = WsFrame.text("hello")
        assertTrue(f.fin)
        assertEquals(WsOpcode.TEXT, f.opcode)
        assertContentEquals("hello".encodeToByteArray(), f.payload)
        assertNull(f.maskKey)
    }

    @Test
    fun binaryFrameFactory() {
        val data = byteArrayOf(1, 2, 3)
        val f = WsFrame.binary(data)
        assertTrue(f.fin)
        assertEquals(WsOpcode.BINARY, f.opcode)
        assertContentEquals(data, f.payload)
    }

    @Test
    fun pingFrameFactory() {
        val f = WsFrame.ping()
        assertTrue(f.fin)
        assertEquals(WsOpcode.PING, f.opcode)
        assertEquals(0, f.payload.size)
    }

    @Test
    fun pongFactoryWithData() {
        val f = WsFrame.pong("pong".encodeToByteArray())
        assertTrue(f.fin)
        assertEquals(WsOpcode.PONG, f.opcode)
        assertContentEquals("pong".encodeToByteArray(), f.payload)
    }

    @Test
    fun closeFrameWithCode() {
        val f = WsFrame.close(WsCloseCode.NORMAL_CLOSURE)
        assertTrue(f.fin)
        assertEquals(WsOpcode.CLOSE, f.opcode)
        assertEquals(2, f.payload.size)
        assertEquals(0x03.toByte(), f.payload[0])
        assertEquals(0xE8.toByte(), f.payload[1])
    }

    @Test
    fun closeFrameWithReason() {
        val f = WsFrame.close(WsCloseCode.GOING_AWAY, "bye")
        assertEquals(5, f.payload.size)  // 2 bytes code + 3 bytes "bye"
        assertEquals(0x03.toByte(), f.payload[0])
        assertEquals(0xE9.toByte(), f.payload[1])
        assertContentEquals("bye".encodeToByteArray(), f.payload.copyOfRange(2, 5))
    }

    @Test
    fun closeFrameEmpty() {
        val f = WsFrame.close()
        assertEquals(0, f.payload.size)
    }

    @Test
    fun continuationFragment() {
        val f = WsFrame.continuation("part".encodeToByteArray(), fin = false)
        assertFalse(f.fin)
        assertEquals(WsOpcode.CONTINUATION, f.opcode)
    }

    @Test
    fun controlFrameOversizeThrows() {
        assertFailsWith<IllegalArgumentException> { WsFrame.ping(ByteArray(126)) }
    }

    @Test
    fun controlFrameFragmentedThrows() {
        assertFailsWith<IllegalArgumentException> {
            WsFrame(fin = false, opcode = WsOpcode.PING)
        }
    }

    @Test
    fun rsvDefaultFalse() {
        val f = WsFrame.text("x")
        assertFalse(f.rsv1)
        assertFalse(f.rsv2)
        assertFalse(f.rsv3)
    }

    @Test
    fun maskedFrame() {
        val f = WsFrame.text("hello", maskKey = 0x12345678)
        assertEquals(0x12345678, f.maskKey)
    }
}
