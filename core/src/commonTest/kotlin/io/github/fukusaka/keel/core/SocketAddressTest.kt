package io.github.fukusaka.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SocketAddressTest {

    @Test
    fun `equals for same host and port`() {
        val a = SocketAddress("127.0.0.1", 8080)
        val b = SocketAddress("127.0.0.1", 8080)
        assertEquals(a, b)
    }

    @Test
    fun `not equals for different port`() {
        val a = SocketAddress("127.0.0.1", 8080)
        val b = SocketAddress("127.0.0.1", 9090)
        assertNotEquals(a, b)
    }

    @Test
    fun `not equals for different host`() {
        val a = SocketAddress("127.0.0.1", 8080)
        val b = SocketAddress("0.0.0.0", 8080)
        assertNotEquals(a, b)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val a = SocketAddress("127.0.0.1", 8080)
        val b = SocketAddress("127.0.0.1", 8080)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString contains host and port`() {
        val addr = SocketAddress("192.168.1.1", 443)
        val str = addr.toString()
        assertTrue(str.contains("192.168.1.1"), "toString should contain host: $str")
        assertTrue(str.contains("443"), "toString should contain port: $str")
    }

    @Test
    fun `copy with different port`() {
        val addr = SocketAddress("127.0.0.1", 8080)
        val copied = addr.copy(port = 9090)
        assertEquals("127.0.0.1", copied.host)
        assertEquals(9090, copied.port)
    }

    @Test
    fun `port zero for ephemeral`() {
        val addr = SocketAddress("0.0.0.0", 0)
        assertEquals(0, addr.port)
    }
}
