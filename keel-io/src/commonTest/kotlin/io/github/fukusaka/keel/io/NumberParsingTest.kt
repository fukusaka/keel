package io.github.fukusaka.keel.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NumberParsingTest {

    // --- toDecLongOrNull ---

    @Test
    fun `decimal — empty input returns null`() {
        assertNull("".toDecLongOrNull())
    }

    @Test
    fun `decimal — single zero parses`() {
        assertEquals(0L, "0".toDecLongOrNull())
    }

    @Test
    fun `decimal — single digit parses`() {
        assertEquals(7L, "7".toDecLongOrNull())
    }

    @Test
    fun `decimal — multi-digit parses`() {
        assertEquals(12345L, "12345".toDecLongOrNull())
    }

    @Test
    fun `decimal — Long MAX_VALUE parses`() {
        assertEquals(Long.MAX_VALUE, Long.MAX_VALUE.toString().toDecLongOrNull())
    }

    @Test
    fun `decimal — Long MIN_VALUE parses`() {
        assertEquals(Long.MIN_VALUE, Long.MIN_VALUE.toString().toDecLongOrNull())
    }

    @Test
    fun `decimal — overflow above MAX_VALUE returns null`() {
        // Long.MAX_VALUE + 1 = 9223372036854775808
        assertNull("9223372036854775808".toDecLongOrNull())
    }

    @Test
    fun `decimal — underflow below MIN_VALUE returns null`() {
        // Long.MIN_VALUE - 1 = -9223372036854775809
        assertNull("-9223372036854775809".toDecLongOrNull())
    }

    @Test
    fun `decimal — leading minus parses negative`() {
        assertEquals(-42L, "-42".toDecLongOrNull())
    }

    @Test
    fun `decimal — leading plus parses positive`() {
        assertEquals(42L, "+42".toDecLongOrNull())
    }

    @Test
    fun `decimal — sign-only returns null`() {
        assertNull("-".toDecLongOrNull())
        assertNull("+".toDecLongOrNull())
    }

    @Test
    fun `decimal — non-digit char returns null`() {
        assertNull("12a45".toDecLongOrNull())
        assertNull("1 2".toDecLongOrNull())
    }

    @Test
    fun `decimal — leading whitespace returns null - no auto-trim`() {
        assertNull(" 42".toDecLongOrNull())
    }

    @Test
    fun `decimal — trailing whitespace returns null - no auto-trim`() {
        assertNull("42 ".toDecLongOrNull())
    }

    @Test
    fun `decimal — hex-looking input returns null`() {
        assertNull("ff".toDecLongOrNull())
        assertNull("0x10".toDecLongOrNull())
    }

    @Test
    fun `decimal — works on non-String CharSequence`() {
        val sb: CharSequence = StringBuilder("12345").subSequence(1, 4)
        assertEquals(234L, sb.toDecLongOrNull())
    }

    @Test
    fun `decimal — matches String_toLongOrNull on broad sample`() {
        val samples = listOf(
            "", "0", "1", "-1", "+1", "10", "-10", "12345", "-12345",
            "9223372036854775807", "9223372036854775808",
            "-9223372036854775808", "-9223372036854775809",
            "abc", "1a", " 1", "1 ", "+", "-", "++1", "--1", "1.0",
        )
        for (s in samples) {
            assertEquals(s.toLongOrNull(), s.toDecLongOrNull(), "input='$s'")
        }
    }

    // --- toHexLongOrNull ---

    @Test
    fun `hex — empty input returns null`() {
        assertNull("".toHexLongOrNull())
    }

    @Test
    fun `hex — single zero parses`() {
        assertEquals(0L, "0".toHexLongOrNull())
    }

    @Test
    fun `hex — lowercase hex digits parse`() {
        assertEquals(0xffL, "ff".toHexLongOrNull())
        assertEquals(0xdeadbeefL, "deadbeef".toHexLongOrNull())
    }

    @Test
    fun `hex — uppercase hex digits parse`() {
        assertEquals(0xffL, "FF".toHexLongOrNull())
        assertEquals(0xdeadbeefL, "DEADBEEF".toHexLongOrNull())
    }

    @Test
    fun `hex — mixed case parses`() {
        assertEquals(0xabcdefL, "AbCdEf".toHexLongOrNull())
    }

    @Test
    fun `hex — Long MAX_VALUE in hex parses`() {
        assertEquals(Long.MAX_VALUE, "7fffffffffffffff".toHexLongOrNull())
    }

    @Test
    fun `hex — overflow returns null`() {
        // 0x10000000000000000 = 2^64 — overflow
        assertNull("10000000000000000".toHexLongOrNull())
    }

    @Test
    fun `hex — leading minus parses negative`() {
        assertEquals(-0x2aL, "-2a".toHexLongOrNull())
    }

    @Test
    fun `hex — 0x prefix returns null`() {
        assertNull("0xff".toHexLongOrNull())
        assertNull("0X10".toHexLongOrNull())
    }

    @Test
    fun `hex — non-hex char returns null`() {
        assertNull("g0".toHexLongOrNull())
        assertNull("ff!".toHexLongOrNull())
    }

    @Test
    fun `hex — whitespace returns null - no auto-trim`() {
        assertNull(" ff".toHexLongOrNull())
        assertNull("ff ".toHexLongOrNull())
    }

    @Test
    fun `hex — sign-only returns null`() {
        assertNull("-".toHexLongOrNull())
        assertNull("+".toHexLongOrNull())
    }

    @Test
    fun `hex — works on non-String CharSequence`() {
        val sb: CharSequence = StringBuilder("xdeadbeefy").subSequence(1, 9)
        assertEquals(0xdeadbeefL, sb.toHexLongOrNull())
    }

    @Test
    fun `hex — matches String_toLongOrNull radix 16 on broad sample`() {
        val samples = listOf(
            "", "0", "1", "ff", "FF", "deadbeef", "DEADBEEF", "AbCdEf",
            "7fffffffffffffff", "8000000000000000",
            "-8000000000000000", "-8000000000000001",
            "10000000000000000", "g", "ff!", " ff", "ff ", "+", "-",
            "0x10", "0X10",
        )
        for (s in samples) {
            assertEquals(s.toLongOrNull(16), s.toHexLongOrNull(), "input='$s'")
        }
    }
}
