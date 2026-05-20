package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IoBufByteCharSequenceTest {

    private fun bufOf(ascii: String): IoBuf {
        val bytes = ascii.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size.coerceAtLeast(1))
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    @Test
    fun `view exposes the byte range as ascii chars`() {
        val buf = bufOf("Hello, World!")
        val seq = IoBufByteCharSequence(buf, 0, 13)
        assertEquals(13, seq.length)
        assertEquals('H', seq[0])
        assertEquals('W', seq[7])
        assertEquals('!', seq[12])
        buf.release()
    }

    @Test
    fun `view sub-range starts at the requested offset`() {
        val buf = bufOf("application/json")
        val seq = IoBufByteCharSequence(buf, 12, 4)
        assertEquals(4, seq.length)
        assertEquals('j', seq[0])
        assertEquals('s', seq[1])
        assertEquals('o', seq[2])
        assertEquals('n', seq[3])
        buf.release()
    }

    @Test
    fun `subSequence returns a sub-range over the same backing`() {
        val buf = bufOf("application/json")
        val seq = IoBufByteCharSequence(buf, 0, 16)
        val sub = seq.subSequence(12, 16)
        assertEquals(4, sub.length)
        assertEquals('j', sub[0])
        assertEquals('n', sub[3])
        buf.release()
    }

    @Test
    fun `toString decodes the byte range as utf-8`() {
        val buf = bufOf("text/plain")
        val seq = IoBufByteCharSequence(buf, 0, 10)
        assertEquals("text/plain", seq.toString())
        buf.release()
    }

    @Test
    fun `toString of empty view is empty`() {
        val buf = bufOf("anything")
        val seq = IoBufByteCharSequence(buf, 0, 0)
        assertEquals("", seq.toString())
        buf.release()
    }

    @Test
    fun `hashCode matches the ascii String hashCode`() {
        val text = "Content-Type"
        val buf = bufOf(text)
        val seq = IoBufByteCharSequence(buf, 0, text.length)
        assertEquals(text.hashCode(), seq.hashCode())
        buf.release()
    }

    @Test
    fun `contentEquals matches Kotlin stdlib comparison`() {
        val buf = bufOf("application/json")
        val seq = IoBufByteCharSequence(buf, 0, 16)
        assertTrue(seq.contentEquals("application/json"))
        assertTrue(!seq.contentEquals("application/jsonX"))
        assertTrue(!seq.contentEquals("application/jso"))
        // CharSequence.contentEquals uses per-char compare, so view <-> view works too
        val other = IoBufByteCharSequence(buf, 0, 16)
        assertTrue(seq.contentEquals(other))
        buf.release()
    }

    @Test
    fun `equals true for two views over identical bytes`() {
        val buf1 = bufOf("text/plain")
        val buf2 = bufOf("text/plain")
        val a = IoBufByteCharSequence(buf1, 0, 10)
        val b = IoBufByteCharSequence(buf2, 0, 10)
        assertEquals(a, b)
        buf1.release()
        buf2.release()
    }

    @Test
    fun `equals false for differing length or bytes`() {
        val buf = bufOf("text/plain")
        val a = IoBufByteCharSequence(buf, 0, 10)
        val b = IoBufByteCharSequence(buf, 0, 9)
        val c = IoBufByteCharSequence(buf, 1, 9)
        assertTrue(a != b, "length differs")
        assertTrue(a != c, "bytes differ")
        buf.release()
    }

    @Test
    fun `get out-of-bounds throws IndexOutOfBoundsException`() {
        val buf = bufOf("abc")
        val seq = IoBufByteCharSequence(buf, 0, 3)
        assertFailsWith<IndexOutOfBoundsException> { seq[-1] }
        assertFailsWith<IndexOutOfBoundsException> { seq[3] }
        buf.release()
    }

    @Test
    fun `subSequence out-of-bounds throws IndexOutOfBoundsException`() {
        val buf = bufOf("abc")
        val seq = IoBufByteCharSequence(buf, 0, 3)
        assertFailsWith<IndexOutOfBoundsException> { seq.subSequence(-1, 2) }
        assertFailsWith<IndexOutOfBoundsException> { seq.subSequence(0, 4) }
        assertFailsWith<IndexOutOfBoundsException> { seq.subSequence(2, 1) }
        buf.release()
    }

    @Test
    fun `hashCode is cached after first call`() {
        val text = "Content-Type"
        val buf = bufOf(text)
        val seq = IoBufByteCharSequence(buf, 0, text.length)
        val first = seq.hashCode()
        val second = seq.hashCode()
        assertEquals(first, second, "cached call must return the same hash")
        assertEquals(text.hashCode(), second)
        buf.release()
    }

    @Test
    fun `hashCode of empty view is zero`() {
        val buf = bufOf("anything")
        val seq = IoBufByteCharSequence(buf, 0, 0)
        assertEquals(0, seq.hashCode())
        buf.release()
    }

    @Test
    fun `contentEquals member overload matches stdlib semantics for String`() {
        val buf = bufOf("application/json")
        val seq = IoBufByteCharSequence(buf, 0, 16)
        assertTrue(seq.contentEquals("application/json"))
        assertTrue(!seq.contentEquals("application/jsonX"))
        assertTrue(!seq.contentEquals("application/jso"))
        assertTrue(!seq.contentEquals(""))
        buf.release()
    }

    @Test
    fun `contentEquals member overload matches stdlib semantics for CharSequence`() {
        val buf = bufOf("application/json")
        val seq = IoBufByteCharSequence(buf, 0, 16)
        val sb: CharSequence = StringBuilder("application/json")
        assertTrue(seq.contentEquals(sb))
        val other = IoBufByteCharSequence(buf, 0, 16)
        assertTrue(seq.contentEquals(other as CharSequence))
        buf.release()
    }

    @Test
    fun `contentEqualsAscii compares bytes byte-for-byte`() {
        val buf = bufOf("application/json")
        val seq = IoBufByteCharSequence(buf, 0, 16)
        assertTrue(seq.contentEqualsAscii("application/json".encodeToByteArray()))
        assertTrue(!seq.contentEqualsAscii("application/jsonX".encodeToByteArray()))
        assertTrue(!seq.contentEqualsAscii("application/jso".encodeToByteArray()))
        // empty
        val empty = IoBufByteCharSequence(buf, 0, 0)
        assertTrue(empty.contentEqualsAscii(ByteArray(0)))
        assertTrue(!empty.contentEqualsAscii("a".encodeToByteArray()))
        buf.release()
    }

    @Test
    fun `contentEqualsAscii is byte-equal, not case-insensitive`() {
        val buf = bufOf("Content-Type")
        val seq = IoBufByteCharSequence(buf, 0, 12)
        assertTrue(seq.contentEqualsAscii("Content-Type".encodeToByteArray()))
        // case mismatch must NOT match (caller is responsible for case
        // normalisation if they want case-insensitive compare)
        assertTrue(!seq.contentEqualsAscii("content-type".encodeToByteArray()))
        buf.release()
    }

    @Test
    fun `start plus length must fit within buf capacity`() {
        val buf = bufOf("abc")
        // buf capacity is at least 3 (depends on allocator backing); the
        // strict guard is against passing a window past capacity.
        assertFailsWith<IllegalArgumentException> {
            IoBufByteCharSequence(buf, 0, buf.capacity + 1)
        }
        assertFailsWith<IllegalArgumentException> { IoBufByteCharSequence(buf, -1, 1) }
        assertFailsWith<IllegalArgumentException> { IoBufByteCharSequence(buf, 0, -1) }
        buf.release()
    }
}
