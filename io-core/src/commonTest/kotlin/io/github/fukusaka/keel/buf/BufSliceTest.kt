package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BufSliceTest {

    private fun sliceOf(text: String): BufSlice {
        val buf = DefaultAllocator.allocate(text.length)
        for (b in text.encodeToByteArray()) buf.writeByte(b)
        return BufSlice(buf, 0, text.length)
    }

    // -- byte access --

    @Test
    fun getReturnsCorrectByte() {
        val slice = sliceOf("ABC")
        assertEquals('A'.code.toByte(), slice[0])
        assertEquals('B'.code.toByte(), slice[1])
        assertEquals('C'.code.toByte(), slice[2])
    }

    @Test
    fun getOutOfBoundsThrows() {
        val slice = sliceOf("AB")
        assertFailsWith<IllegalArgumentException> { slice[2] }
        assertFailsWith<IllegalArgumentException> { slice[-1] }
    }

    // -- isEmpty --

    @Test
    fun isEmptyForZeroLength() {
        val buf = DefaultAllocator.allocate(8)
        assertTrue(BufSlice(buf, 0, 0).isEmpty())
        buf.release()
    }

    @Test
    fun isNotEmptyForNonZeroLength() {
        val slice = sliceOf("A")
        assertFalse(slice.isEmpty())
    }

    // -- slice --

    @Test
    fun subSliceReturnsCorrectRange() {
        val slice = sliceOf("Hello World")
        val sub = slice.slice(6, 11)
        assertEquals("World", sub.decodeToString())
    }

    @Test
    fun subSliceOfSubSlice() {
        val slice = sliceOf("ABCDEF")
        val sub = slice.slice(1, 5) // BCDE
        val subsub = sub.slice(1, 3) // CD
        assertEquals("CD", subsub.decodeToString())
    }

    // -- indexOf --

    @Test
    fun indexOfFindsDelimiter() {
        val slice = sliceOf("Host: localhost")
        assertEquals(4, slice.indexOf(':'.code.toByte()))
    }

    @Test
    fun indexOfReturnsMinusOneWhenNotFound() {
        val slice = sliceOf("NoColon")
        assertEquals(-1, slice.indexOf(':'.code.toByte()))
    }

    @Test
    fun indexOfWithFromIndex() {
        val slice = sliceOf("a:b:c")
        assertEquals(3, slice.indexOf(':'.code.toByte(), fromIndex = 2))
    }

    // -- contentEquals --

    @Test
    fun contentEqualsWithString() {
        val slice = sliceOf("GET")
        assertTrue(slice.contentEquals("GET"))
        assertFalse(slice.contentEquals("POST"))
        assertFalse(slice.contentEquals("GE"))
    }

    @Test
    fun contentEqualsWithBufSlice() {
        val a = sliceOf("HTTP/1.1")
        val b = sliceOf("HTTP/1.1")
        assertTrue(a.contentEquals(b))
    }

    @Test
    fun contentEqualsIgnoreCaseMatches() {
        val slice = sliceOf("Content-Type")
        assertTrue(slice.contentEqualsIgnoreCase("content-type"))
        assertTrue(slice.contentEqualsIgnoreCase("CONTENT-TYPE"))
        assertFalse(slice.contentEqualsIgnoreCase("Content-Length"))
    }

    // -- trim --

    @Test
    fun trimRemovesLeadingAndTrailingWhitespace() {
        val slice = sliceOf("  hello  ")
        assertEquals("hello", slice.trim().decodeToString())
    }

    @Test
    fun trimHandlesTabs() {
        val slice = sliceOf("\t value \t")
        assertEquals("value", slice.trim().decodeToString())
    }

    @Test
    fun trimNoOpWhenNoWhitespace() {
        val slice = sliceOf("clean")
        val trimmed = slice.trim()
        assertEquals("clean", trimmed.decodeToString())
        assertEquals(slice.offset, trimmed.offset) // same object or same range
    }

    // -- decodeToString --

    @Test
    fun decodeToStringReturnsCorrectString() {
        val slice = sliceOf("HTTP/1.1 200 OK")
        assertEquals("HTTP/1.1 200 OK", slice.decodeToString())
    }

    @Test
    fun decodeToStringEmptySlice() {
        val buf = DefaultAllocator.allocate(8)
        assertEquals("", BufSlice(buf, 0, 0).decodeToString())
        buf.release()
    }

    // -- toByteArray --

    @Test
    fun toByteArrayCopiesCorrectBytes() {
        val slice = sliceOf("ABC")
        val bytes = slice.toByteArray()
        assertEquals(3, bytes.size)
        assertEquals('A'.code.toByte(), bytes[0])
        assertEquals('C'.code.toByte(), bytes[2])
    }

    // -- toInt --

    @Test
    fun toIntParsesDecimal() {
        assertEquals(200, sliceOf("200").toInt())
        assertEquals(404, sliceOf("404").toInt())
        assertEquals(0, sliceOf("0").toInt())
    }

    @Test
    fun toIntThrowsOnNonDigit() {
        assertFailsWith<NumberFormatException> { sliceOf("abc").toInt() }
        assertFailsWith<NumberFormatException> { sliceOf("").toInt() }
    }

    // -- offset slice (non-zero offset) --

    @Test
    fun sliceWithNonZeroOffset() {
        val buf = DefaultAllocator.allocate(16)
        for (b in "GET /hello HTTP/".encodeToByteArray()) buf.writeByte(b)
        // Slice starting at offset 4 (the URI)
        val uri = BufSlice(buf, 4, 6) // "/hello"
        assertEquals("/hello", uri.decodeToString())
        assertTrue(uri.contentEquals("/hello"))
        buf.release()
    }

    // ============================================================
    // Multi-segment (next chain) tests
    // ============================================================

    private fun chainSlice(first: String, second: String): Triple<BufSlice, IoBuf, IoBuf> {
        val buf1 = DefaultAllocator.allocate(first.length)
        for (b in first.encodeToByteArray()) buf1.writeByte(b)
        val buf2 = DefaultAllocator.allocate(second.length)
        for (b in second.encodeToByteArray()) buf2.writeByte(b)
        val slice = BufSlice(buf1, 0, first.length, BufSlice(buf2, 0, second.length))
        return Triple(slice, buf1, buf2)
    }

    @Test
    fun chain_totalLength() {
        val (slice, buf1, buf2) = chainSlice("Hello", "World")
        assertEquals(10, slice.totalLength)
        assertEquals(5, slice.length)
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_get() {
        val (slice, buf1, buf2) = chainSlice("AB", "CD")
        assertEquals('A'.code.toByte(), slice[0])
        assertEquals('B'.code.toByte(), slice[1])
        assertEquals('C'.code.toByte(), slice[2])
        assertEquals('D'.code.toByte(), slice[3])
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_indexOf() {
        val (slice, buf1, buf2) = chainSlice("Hel", "lo!")
        assertEquals(2, slice.indexOf('l'.code.toByte()))  // in first segment
        assertEquals(3, slice.indexOf('l'.code.toByte(), 3))  // in second segment
        assertEquals(5, slice.indexOf('!'.code.toByte()))
        assertEquals(-1, slice.indexOf('?'.code.toByte()))
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_contentEquals_string() {
        val (slice, buf1, buf2) = chainSlice("Con", "tent")
        assertTrue(slice.contentEquals("Content"))
        assertFalse(slice.contentEquals("Conten"))
        assertFalse(slice.contentEquals("ContentX"))
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_contentEqualsIgnoreCase() {
        val (slice, buf1, buf2) = chainSlice("con", "TENT")
        assertTrue(slice.contentEqualsIgnoreCase("Content"))
        assertTrue(slice.contentEqualsIgnoreCase("CONTENT"))
        assertFalse(slice.contentEqualsIgnoreCase("Conten"))
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_contentEquals_bufSlice() {
        val (slice1, buf1a, buf1b) = chainSlice("He", "llo")
        val buf2 = DefaultAllocator.allocate(5)
        for (b in "Hello".encodeToByteArray()) buf2.writeByte(b)
        val slice2 = BufSlice(buf2, 0, 5)
        assertTrue(slice1.contentEquals(slice2))
        buf1a.release(); buf1b.release(); buf2.release()
    }

    @Test
    fun chain_decodeToString() {
        val (slice, buf1, buf2) = chainSlice("Hello", " World")
        assertEquals("Hello World", slice.decodeToString())
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_toByteArray() {
        val (slice, buf1, buf2) = chainSlice("AB", "CD")
        val bytes = slice.toByteArray()
        assertEquals(4, bytes.size)
        assertEquals("ABCD", bytes.decodeToString())
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_toInt() {
        val (slice, buf1, buf2) = chainSlice("12", "34")
        assertEquals(1234, slice.toInt())
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_trim() {
        val (slice, buf1, buf2) = chainSlice("  He", "llo  ")
        val trimmed = slice.trim()
        assertEquals("Hello", trimmed.decodeToString())
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_slice_within_first() {
        val (slice, buf1, buf2) = chainSlice("Hello", "World")
        val sub = slice.slice(1, 4) // "ell"
        assertEquals("ell", sub.decodeToString())
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_slice_spanning() {
        val (slice, buf1, buf2) = chainSlice("Hello", "World")
        val sub = slice.slice(3, 8) // "loWor"
        assertEquals("loWor", sub.decodeToString())
        assertEquals(5, sub.totalLength)
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_slice_within_second() {
        val (slice, buf1, buf2) = chainSlice("Hello", "World")
        val sub = slice.slice(6, 9) // "orl"
        assertEquals("orl", sub.decodeToString())
        buf1.release(); buf2.release()
    }

    @Test
    fun chain_isEmpty() {
        val buf = DefaultAllocator.allocate(4)
        buf.writeByte(0)
        val empty = BufSlice(buf, 0, 0, null)
        assertTrue(empty.isEmpty())
        val (nonEmpty, buf1, buf2) = chainSlice("A", "B")
        assertFalse(nonEmpty.isEmpty())
        buf.release(); buf1.release(); buf2.release()
    }
}
