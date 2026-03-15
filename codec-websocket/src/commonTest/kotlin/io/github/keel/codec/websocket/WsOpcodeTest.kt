package io.github.keel.codec.websocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WsOpcodeTest {

    @Test
    fun continuationIsCode0() {
        assertEquals(0, WsOpcode.CONTINUATION.code)
    }

    @Test
    fun textIsCode1() {
        assertEquals(1, WsOpcode.TEXT.code)
    }

    @Test
    fun binaryIsCode2() {
        assertEquals(2, WsOpcode.BINARY.code)
    }

    @Test
    fun closeIsCode8() {
        assertEquals(8, WsOpcode.CLOSE.code)
        assertTrue(WsOpcode.CLOSE.isControl)
    }

    @Test
    fun pingIsCode9() {
        assertEquals(9, WsOpcode.PING.code)
        assertTrue(WsOpcode.PING.isControl)
    }

    @Test
    fun pongIsCode10() {
        assertEquals(10, WsOpcode.PONG.code)
        assertTrue(WsOpcode.PONG.isControl)
    }

    @Test
    fun dataFramesNotControl() {
        assertFalse(WsOpcode.CONTINUATION.isControl)
        assertFalse(WsOpcode.TEXT.isControl)
        assertFalse(WsOpcode.BINARY.isControl)
    }

    @Test
    fun isDataForDataFrames() {
        assertTrue(WsOpcode.CONTINUATION.isData)
        assertTrue(WsOpcode.TEXT.isData)
        assertTrue(WsOpcode.BINARY.isData)
        assertFalse(WsOpcode.CLOSE.isData)
        assertFalse(WsOpcode.PING.isData)
        assertFalse(WsOpcode.PONG.isData)
    }

    @Test
    fun fromCodeThrowsOnUnknown() {
        assertFailsWith<IllegalArgumentException> { WsOpcode.fromCode(3) }
        assertFailsWith<IllegalArgumentException> { WsOpcode.fromCode(0xB) }
        assertFailsWith<IllegalArgumentException> { WsOpcode.fromCode(0xF) }
    }

    @Test
    fun fromCodeReturnsKnownOpcodes() {
        assertEquals(WsOpcode.TEXT, WsOpcode.fromCode(1))
        assertEquals(WsOpcode.PING, WsOpcode.fromCode(9))
    }
}
