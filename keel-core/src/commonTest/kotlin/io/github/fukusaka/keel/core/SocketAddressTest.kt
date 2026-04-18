package io.github.fukusaka.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SocketAddressTest {

    @Test
    fun `string constructor parses IPv4 literal eagerly`() {
        val addr = InetSocketAddress("127.0.0.1", 8080)
        assertIs<Host.Ip>(addr.host)
        assertIs<IpAddress.V4>((addr.host as Host.Ip).address)
        assertEquals(8080, addr.port)
        assertFalse(addr.isUnresolved)
        assertEquals("127.0.0.1", addr.hostString)
    }

    @Test
    fun `string constructor parses IPv6 literal eagerly`() {
        val addr = InetSocketAddress("::1", 443)
        assertIs<Host.Ip>(addr.host)
        assertIs<IpAddress.V6>((addr.host as Host.Ip).address)
        assertEquals(443, addr.port)
        assertFalse(addr.isUnresolved)
        assertEquals("::1", addr.hostString)
    }

    @Test
    fun `string constructor keeps hostname as Host_Name`() {
        val addr = InetSocketAddress("example.com", 80)
        assertIs<Host.Name>(addr.host)
        assertEquals("example.com", (addr.host as Host.Name).value)
        assertTrue(addr.isUnresolved)
    }

    @Test
    fun `equals for same host and port`() {
        val a = InetSocketAddress("127.0.0.1", 8080)
        val b = InetSocketAddress("127.0.0.1", 8080)
        assertEquals(a, b)
    }

    @Test
    fun `not equals for different port`() {
        val a = InetSocketAddress("127.0.0.1", 8080)
        val b = InetSocketAddress("127.0.0.1", 9090)
        assertNotEquals(a, b)
    }

    @Test
    fun `not equals for different host`() {
        val a = InetSocketAddress("127.0.0.1", 8080)
        val b = InetSocketAddress("0.0.0.0", 8080)
        assertNotEquals(a, b)
    }

    @Test
    fun `hostname and resolved IP compare unequal`() {
        val hostname = InetSocketAddress("localhost", 8080)
        val resolved = InetSocketAddress("127.0.0.1", 8080)
        assertNotEquals(hostname, resolved)
    }

    @Test
    fun `hashCode is consistent with equals`() {
        val a = InetSocketAddress("127.0.0.1", 8080)
        val b = InetSocketAddress("127.0.0.1", 8080)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `toString for IPv4 uses dotted decimal`() {
        val addr = InetSocketAddress("192.168.1.1", 443)
        assertEquals("192.168.1.1:443", addr.toString())
    }

    @Test
    fun `toString for IPv6 uses bracket notation`() {
        val addr = InetSocketAddress("::1", 443)
        assertEquals("[::1]:443", addr.toString())
    }

    @Test
    fun `toString for hostname leaves it bare`() {
        val addr = InetSocketAddress("example.com", 80)
        assertEquals("example.com:80", addr.toString())
    }

    @Test
    fun `copy with different port`() {
        val addr = InetSocketAddress("127.0.0.1", 8080)
        val copied = addr.copy(port = 9090)
        assertEquals(addr.host, copied.host)
        assertEquals(9090, copied.port)
    }

    @Test
    fun `port zero for ephemeral`() {
        val addr = InetSocketAddress("0.0.0.0", 0)
        assertEquals(0, addr.port)
    }

    @Test
    fun `port out of range rejected`() {
        assertFailsWith<IllegalArgumentException> { InetSocketAddress("127.0.0.1", -1) }
        assertFailsWith<IllegalArgumentException> { InetSocketAddress("127.0.0.1", 65536) }
    }

    @Test
    fun `UnixSocketAddress holds path`() {
        val addr = UnixSocketAddress("/tmp/foo.sock")
        assertEquals("/tmp/foo.sock", addr.path)
        assertEquals("unix:/tmp/foo.sock", addr.toString())
    }

    @Test
    fun `UnixSocketAddress equals by path`() {
        val a = UnixSocketAddress("/var/run/a.sock")
        val b = UnixSocketAddress("/var/run/a.sock")
        val c = UnixSocketAddress("/var/run/b.sock")
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test
    fun `UnixSocketAddress filesystem factory`() {
        val addr = UnixSocketAddress.filesystem("/tmp/foo.sock")
        assertFalse(addr.isAbstract)
        assertEquals("/tmp/foo.sock", addr.path)
        assertEquals("/tmp/foo.sock", addr.kernelPath)
        assertEquals("unix:/tmp/foo.sock", addr.toString())
    }

    @Test
    fun `UnixSocketAddress abstract factory with at-prefix`() {
        val addr = UnixSocketAddress.abstract("myapp")
        assertTrue(addr.isAbstract)
        assertEquals("@myapp", addr.path)
        assertEquals("\u0000myapp", addr.kernelPath)
        assertEquals("unix:@myapp", addr.toString())
    }

    @Test
    fun `UnixSocketAddress constructor accepts at-prefix as abstract`() {
        val addr = UnixSocketAddress("@direct")
        assertTrue(addr.isAbstract)
        assertEquals("\u0000direct", addr.kernelPath)
        assertEquals("unix:@direct", addr.toString())
    }

    @Test
    fun `UnixSocketAddress constructor accepts literal null byte as abstract`() {
        // Go / Netty / Python conventions pass leading \u0000 directly.
        val addr = UnixSocketAddress("\u0000fromNul")
        assertTrue(addr.isAbstract)
        assertEquals("\u0000fromNul", addr.kernelPath)
        // Display form uses the readable @prefix even when stored as \u0000.
        assertEquals("unix:@fromNul", addr.toString())
    }

    @Test
    fun `UnixSocketAddress at and null-byte forms are not equal as data classes`() {
        // Documented contract: field-wise equality; factory is the
        // recommended way to avoid surprises.
        val a = UnixSocketAddress("@foo")
        val b = UnixSocketAddress("\u0000foo")
        assertNotEquals(a, b)
        // But both map to the same kernel representation.
        assertEquals(a.kernelPath, b.kernelPath)
    }

    @Test
    fun `sealed hierarchy covers both subtypes`() {
        val inet: SocketAddress = InetSocketAddress("127.0.0.1", 80)
        val unix: SocketAddress = UnixSocketAddress("/tmp/x")
        val kind = when (inet) {
            is InetSocketAddress -> "inet"
            is UnixSocketAddress -> "unix"
        }
        assertEquals("inet", kind)
        val kind2 = when (unix) {
            is InetSocketAddress -> "inet"
            is UnixSocketAddress -> "unix"
        }
        assertEquals("unix", kind2)
    }
}
