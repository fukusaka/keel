package io.github.fukusaka.keel.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BufSliceTest {

    private fun sliceOf(text: String): BufSlice {
        val buf = HeapAllocator.allocate(text.length)
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
        val buf = HeapAllocator.allocate(8)
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
        val buf = HeapAllocator.allocate(8)
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
        val buf = HeapAllocator.allocate(16)
        for (b in "GET /hello HTTP/".encodeToByteArray()) buf.writeByte(b)
        // Slice starting at offset 4 (the URI)
        val uri = BufSlice(buf, 4, 6) // "/hello"
        assertEquals("/hello", uri.decodeToString())
        assertTrue(uri.contentEquals("/hello"))
        buf.release()
    }
}
