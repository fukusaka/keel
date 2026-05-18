package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.createDefaultIoBuf
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

    // --- IoBuf.parseDecLongAt / parseHexLongAt ---

    /** Builds an [IoBuf] holding [content] as ASCII bytes from index 0. */
    private fun bufOf(content: String): IoBuf {
        val buf = createDefaultIoBuf(content.length.coerceAtLeast(1))
        if (content.isNotEmpty()) buf.writeAscii(content, 0, content.length)
        return buf
    }

    private inline fun withBuf(content: String, block: (IoBuf) -> Unit) {
        val buf = bufOf(content)
        try {
            block(buf)
        } finally {
            buf.release()
        }
    }

    @Test
    fun `parseDecLongAt — parses the whole buffer`() {
        withBuf("12345") { assertEquals(12345L, it.parseDecLongAt(0, 5)) }
    }

    @Test
    fun `parseDecLongAt — parses a byte range at an offset`() {
        // Surrounding bytes are not part of the range and must be ignored.
        withBuf("XX12345YY") { assertEquals(12345L, it.parseDecLongAt(2, 5)) }
    }

    @Test
    fun `parseDecLongAt — zero length returns null`() {
        withBuf("12345") { assertNull(it.parseDecLongAt(0, 0)) }
    }

    @Test
    fun `parseDecLongAt — a negative sign parses`() {
        withBuf("-42") { assertEquals(-42L, it.parseDecLongAt(0, 3)) }
    }

    @Test
    fun `parseDecLongAt — a non-digit byte in range returns null`() {
        withBuf("12a45") { assertNull(it.parseDecLongAt(0, 5)) }
    }

    @Test
    fun `parseDecLongAt — overflow returns null`() {
        withBuf("9223372036854775808") { assertNull(it.parseDecLongAt(0, 19)) }
    }

    @Test
    fun `parseDecLongAt — Long bounds parse`() {
        withBuf("9223372036854775807") { assertEquals(Long.MAX_VALUE, it.parseDecLongAt(0, 19)) }
        withBuf("-9223372036854775808") { assertEquals(Long.MIN_VALUE, it.parseDecLongAt(0, 20)) }
    }

    @Test
    fun `parseDecLongAt — matches the CharSequence parser on a broad sample`() {
        val samples = listOf("0", "7", "12345", "-42", "+99", "00010", "12a45", "9999999999999999999")
        for (s in samples) {
            withBuf(s) { assertEquals(s.toDecLongOrNull(), it.parseDecLongAt(0, s.length), "input='$s'") }
        }
    }

    @Test
    fun `parseHexLongAt — parses hex bytes`() {
        withBuf("ff") { assertEquals(255L, it.parseHexLongAt(0, 2)) }
        withBuf("1A2b") { assertEquals(0x1A2bL, it.parseHexLongAt(0, 4)) }
    }

    @Test
    fun `parseHexLongAt — parses a byte range at an offset`() {
        // The chunked transfer-encoding use case: a chunk-size token mid-buffer.
        withBuf("\r\ndeadbeef\r\n") { assertEquals(0xdeadbeefL, it.parseHexLongAt(2, 8)) }
    }

    @Test
    fun `parseHexLongAt — a non-hex byte returns null`() {
        withBuf("12g4") { assertNull(it.parseHexLongAt(0, 4)) }
    }

    @Test
    fun `parseHexLongAt — matches the CharSequence parser on a broad sample`() {
        val samples = listOf("0", "ff", "FF", "deadbeef", "DEADBEEF", "AbCdEf", "g")
        for (s in samples) {
            withBuf(s) { assertEquals(s.toHexLongOrNull(), it.parseHexLongAt(0, s.length), "input='$s'") }
        }
    }
}
