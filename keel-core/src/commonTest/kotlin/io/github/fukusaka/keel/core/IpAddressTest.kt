package io.github.fukusaka.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpAddressTest {

    // --- IPv4 parse ---

    @Test
    fun `parse IPv4 loopback`() {
        val a = IpAddress.parse("127.0.0.1")
        assertIs<IpAddress.V4>(a)
        assertEquals(0x7F000001, a.value)
        assertEquals("127.0.0.1", a.toCanonicalString())
    }

    @Test
    fun `parse IPv4 zero and max`() {
        assertEquals(IpAddress.V4(0), IpAddress.parse("0.0.0.0"))
        assertEquals(IpAddress.V4(-1), IpAddress.parse("255.255.255.255"))
    }

    @Test
    fun `parse IPv4 arbitrary`() {
        val a = IpAddress.parse("192.168.1.1")
        assertIs<IpAddress.V4>(a)
        assertEquals("192.168.1.1", a.toCanonicalString())
    }

    @Test
    fun `parse IPv4 rejects out-of-range octet`() {
        assertNull(IpAddress.parseOrNull("256.0.0.0"))
        assertNull(IpAddress.parseOrNull("1.2.3.4.5"))
        assertNull(IpAddress.parseOrNull("1.2.3"))
        assertNull(IpAddress.parseOrNull("1.2.3."))
    }

    @Test
    fun `parse IPv4 rejects leading zeros`() {
        assertNull(IpAddress.parseOrNull("01.2.3.4"))
        assertNull(IpAddress.parseOrNull("127.0.0.01"))
    }

    // --- IPv6 parse ---

    @Test
    fun `parse IPv6 loopback`() {
        val a = IpAddress.parse("::1")
        assertIs<IpAddress.V6>(a)
        assertEquals(0uL, a.high)
        assertEquals(1uL, a.low)
        assertEquals(0, a.scopeId)
        assertEquals("::1", a.toCanonicalString())
    }

    @Test
    fun `parse IPv6 wildcard`() {
        val a = IpAddress.parse("::")
        assertIs<IpAddress.V6>(a)
        assertEquals(0uL, a.high)
        assertEquals(0uL, a.low)
        assertEquals("::", a.toCanonicalString())
    }

    @Test
    fun `parse IPv6 full form`() {
        val a = IpAddress.parse("2001:db8:85a3:0:0:8a2e:370:7334")
        assertIs<IpAddress.V6>(a)
        // Canonical form compresses the two zero groups.
        assertEquals("2001:db8:85a3::8a2e:370:7334", a.toCanonicalString())
    }

    @Test
    fun `parse IPv6 with leading zeros in group`() {
        val a = IpAddress.parse("2001:0db8::1")
        assertEquals("2001:db8::1", a.toCanonicalString())
    }

    @Test
    fun `parse IPv6 bracketed`() {
        val a = IpAddress.parse("[::1]")
        assertEquals(IpAddress.parse("::1"), a)
    }

    @Test
    fun `parse IPv6 with numeric scope id`() {
        val a = IpAddress.parse("fe80::1%2")
        assertIs<IpAddress.V6>(a)
        assertEquals(2, a.scopeId)
        assertEquals("fe80::1%2", a.toCanonicalString())
    }

    @Test
    fun `parse IPv6 with interface-name scope leaves id zero`() {
        // Interface-name resolution is deferred to platform boundary.
        // Pure-Kotlin parser treats unknown names as scopeId=0.
        val a = IpAddress.parse("fe80::1%eth0")
        assertIs<IpAddress.V6>(a)
        assertEquals(0, a.scopeId)
    }

    @Test
    fun `parse IPv6 with embedded IPv4 suffix`() {
        val a = IpAddress.parse("::ffff:1.2.3.4")
        assertIs<IpAddress.V6>(a)
        val bytes = a.toByteArray()
        // Last 4 bytes match the IPv4 value.
        assertEquals(1, bytes[12].toInt() and 0xFF)
        assertEquals(2, bytes[13].toInt() and 0xFF)
        assertEquals(3, bytes[14].toInt() and 0xFF)
        assertEquals(4, bytes[15].toInt() and 0xFF)
    }

    @Test
    fun `parse IPv6 rejects too many groups`() {
        assertNull(IpAddress.parseOrNull("1:2:3:4:5:6:7:8:9"))
    }

    @Test
    fun `parse IPv6 rejects malformed hex`() {
        assertNull(IpAddress.parseOrNull("xyz::"))
        assertNull(IpAddress.parseOrNull("::gg"))
    }

    // --- Hostname is not an IP literal ---

    @Test
    fun `hostname is not an IP literal`() {
        assertNull(IpAddress.parseOrNull("localhost"))
        assertNull(IpAddress.parseOrNull("example.com"))
    }

    @Test
    fun `empty string is not an IP literal`() {
        assertNull(IpAddress.parseOrNull(""))
    }

    // --- parse() throws on invalid ---

    @Test
    fun `parse throws on invalid input`() {
        assertFailsWith<IllegalStateException> { IpAddress.parse("not-an-ip") }
    }

    // --- ofBytes / toByteArray round-trip ---

    @Test
    fun `V4 ofBytes round-trip`() {
        val a = IpAddress.ofBytes(byteArrayOf(192.toByte(), 168.toByte(), 1, 1))
        assertIs<IpAddress.V4>(a)
        assertEquals("192.168.1.1", a.toCanonicalString())
        val b = IpAddress.ofBytes(a.toByteArray())
        assertEquals(a, b)
    }

    @Test
    fun `V6 ofBytes round-trip`() {
        val raw = ByteArray(16)
        raw[0] = 0x20; raw[1] = 0x01; raw[2] = 0x0d; raw[3] = 0xb8.toByte()
        raw[15] = 0x01
        val a = IpAddress.ofBytes(raw)
        assertIs<IpAddress.V6>(a)
        assertTrue(a.toByteArray().contentEquals(raw))
    }

    @Test
    fun `ofBytes rejects wrong length`() {
        assertFailsWith<IllegalStateException> { IpAddress.ofBytes(ByteArray(5)) }
        assertFailsWith<IllegalStateException> { IpAddress.ofBytes(ByteArray(0)) }
    }

    // --- toCanonicalString (RFC 5952) ---

    @Test
    fun `canonical IPv6 form follows RFC 5952`() {
        // Longest run of zeros compressed.
        assertEquals("2001:db8::1", IpAddress.parse("2001:db8:0:0:0:0:0:1").toCanonicalString())
        // Don't compress a single zero group.
        assertEquals("2001:db8:0:1:1:1:1:1", IpAddress.parse("2001:db8:0:1:1:1:1:1").toCanonicalString())
    }

    // --- equals / hashCode ---

    @Test
    fun `equal V4s compare equal`() {
        val a = IpAddress.V4(0x7F000001)
        val b = IpAddress.V4(0x7F000001)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equal V6s compare equal including scope`() {
        val a = IpAddress.V6(0x20010db800000000uL, 1uL, 2)
        val b = IpAddress.V6(0x20010db800000000uL, 1uL, 2)
        assertEquals(a, b)
        val c = IpAddress.V6(0x20010db800000000uL, 1uL, 3)
        assertNotEquals(a, c)
    }

    @Test
    fun `V4 and V6 are never equal`() {
        assertNotEquals<IpAddress>(IpAddress.parse("127.0.0.1"), IpAddress.parse("::ffff:127.0.0.1"))
    }
}
