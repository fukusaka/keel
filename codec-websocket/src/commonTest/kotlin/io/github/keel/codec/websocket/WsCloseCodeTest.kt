package io.github.keel.codec.websocket

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WsCloseCodeTest {

    @Test
    fun normalClosureCode() {
        assertEquals(1000, WsCloseCode.NORMAL_CLOSURE.code)
    }

    @Test
    fun goingAwayCode() {
        assertEquals(1001, WsCloseCode.GOING_AWAY.code)
    }

    @Test
    fun isPrivateUse() {
        assertTrue(WsCloseCode(4000).isPrivateUse)
        assertTrue(WsCloseCode(4999).isPrivateUse)
        assertFalse(WsCloseCode(1000).isPrivateUse)
        assertFalse(WsCloseCode(3999).isPrivateUse)
    }

    @Test
    fun isReserved() {
        assertTrue(WsCloseCode.NO_STATUS_RCVD.isReserved)
        assertTrue(WsCloseCode.ABNORMAL_CLOSURE.isReserved)
        assertTrue(WsCloseCode.TLS_HANDSHAKE.isReserved)
        assertFalse(WsCloseCode.NORMAL_CLOSURE.isReserved)
        assertFalse(WsCloseCode.PROTOCOL_ERROR.isReserved)
    }

    @Test
    fun invalidCodeThrows() {
        assertFailsWith<IllegalArgumentException> { WsCloseCode(999) }
        assertFailsWith<IllegalArgumentException> { WsCloseCode(5000) }
        assertFailsWith<IllegalArgumentException> { WsCloseCode(0) }
    }

    @Test
    fun customCodeAllowed() {
        val code = WsCloseCode(3001)
        assertEquals(3001, code.code)
    }

    @Test
    fun equalityByCode() {
        assertEquals(WsCloseCode(1000), WsCloseCode.NORMAL_CLOSURE)
        assertEquals(WsCloseCode(1002), WsCloseCode.PROTOCOL_ERROR)
    }
}
