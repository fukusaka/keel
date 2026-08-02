package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IoBufAsciiTextTest {

    private fun bufOf(ascii: String): IoBuf {
        val bytes = ascii.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size.coerceAtLeast(1))
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    @Test
    fun `view exposes the byte range as ascii chars`() {
        val buf = bufOf("Hello, World!")
        val seq = IoBufAsciiText(buf, 0, 13)
        assertEquals(13, seq.length)
        assertEquals('H', seq[0])
        assertEquals('W', seq[7])
        assertEquals('!', seq[12])
        buf.release()
    }

    @Test
    fun `view sub-range starts at the requested offset`() {
        val buf = bufOf("application/json")
        val seq = IoBufAsciiText(buf, 12, 4)
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
        val seq = IoBufAsciiText(buf, 0, 16)
        val sub = seq.subSequence(12, 16)
        assertEquals(4, sub.length)
        assertEquals('j', sub[0])
        assertEquals('n', sub[3])
        buf.release()
    }

    @Test
    fun `toString uses ISO-8859-1 byte-as-char semantics`() {
        // Pure ASCII case: every encoding agrees, sanity check.
        val buf = bufOf("text/plain")
        val seq = IoBufAsciiText(buf, 0, 10)
        assertEquals("text/plain", seq.toString())
        buf.release()
    }

    @Test
    fun `toString preserves CharSequence contract for high bytes`() {
        // High bytes (0x80-0xFF) — obs-text per RFC 7230. ISO-8859-1
        // maps them to chars with the same codepoint. UTF-8 decode
        // would have given replacement chars or combined them into
        // fewer codepoints, breaking the CharSequence contract.
        val highByte = 0xC3.toByte() // 0xC3 standalone is not a valid UTF-8 start
        val anotherHigh = 0xA9.toByte()
        val backing = DefaultAllocator.allocate(2)
        backing.writeByte(highByte)
        backing.writeByte(anotherHigh)
        val seq = IoBufAsciiText(backing, 0, 2)
        assertEquals(2, seq.length)
        // get(i) == ISO-8859-1 codepoint
        assertEquals(0xC3.toChar(), seq[0])
        assertEquals(0xA9.toChar(), seq[1])
        // toString().length == length, toString()[i] == get(i)
        val s = seq.toString()
        assertEquals(seq.length, s.length, "length and toString().length must match")
        assertEquals(seq[0], s[0])
        assertEquals(seq[1], s[1])
        backing.release()
    }

    @Test
    fun `toString of empty view is empty`() {
        val buf = bufOf("anything")
        val seq = IoBufAsciiText(buf, 0, 0)
        assertEquals("", seq.toString())
        buf.release()
    }

    @Test
    fun `hashCode matches the ascii String hashCode`() {
        val text = "Content-Type"
        val buf = bufOf(text)
        val seq = IoBufAsciiText(buf, 0, text.length)
        assertEquals(text.hashCode(), seq.hashCode())
        buf.release()
    }

    @Test
    fun `contentEquals matches Kotlin stdlib comparison`() {
        val buf = bufOf("application/json")
        val seq = IoBufAsciiText(buf, 0, 16)
        assertTrue(seq.contentEquals("application/json"))
        assertTrue(!seq.contentEquals("application/jsonX"))
        assertTrue(!seq.contentEquals("application/jso"))
        // CharSequence.contentEquals uses per-char compare, so view <-> view works too
        val other = IoBufAsciiText(buf, 0, 16)
        assertTrue(seq.contentEquals(other))
        buf.release()
    }

    @Test
    fun `equals true for two views over identical bytes`() {
        val buf1 = bufOf("text/plain")
        val buf2 = bufOf("text/plain")
        val a = IoBufAsciiText(buf1, 0, 10)
        val b = IoBufAsciiText(buf2, 0, 10)
        assertEquals(a, b)
        buf1.release()
        buf2.release()
    }

    @Test
    fun `equals false for differing length or bytes`() {
        val buf = bufOf("text/plain")
        val a = IoBufAsciiText(buf, 0, 10)
        val b = IoBufAsciiText(buf, 0, 9)
        val c = IoBufAsciiText(buf, 1, 9)
        assertTrue(a != b, "length differs")
        assertTrue(a != c, "bytes differ")
        buf.release()
    }

    @Test
    fun `get out-of-bounds throws IndexOutOfBoundsException`() {
        val buf = bufOf("abc")
        val seq = IoBufAsciiText(buf, 0, 3)
        assertFailsWith<IndexOutOfBoundsException> { seq[-1] }
        assertFailsWith<IndexOutOfBoundsException> { seq[3] }
        buf.release()
    }

    @Test
    fun `subSequence out-of-bounds throws IndexOutOfBoundsException`() {
        val buf = bufOf("abc")
        val seq = IoBufAsciiText(buf, 0, 3)
        assertFailsWith<IndexOutOfBoundsException> { seq.subSequence(-1, 2) }
        assertFailsWith<IndexOutOfBoundsException> { seq.subSequence(0, 4) }
        assertFailsWith<IndexOutOfBoundsException> { seq.subSequence(2, 1) }
        buf.release()
    }

    @Test
    fun `hashCode is cached after first call`() {
        val text = "Content-Type"
        val buf = bufOf(text)
        val seq = IoBufAsciiText(buf, 0, text.length)
        val first = seq.hashCode()
        val second = seq.hashCode()
        assertEquals(first, second, "cached call must return the same hash")
        assertEquals(text.hashCode(), second)
        buf.release()
    }

    @Test
    fun `hashCode of empty view is zero`() {
        val buf = bufOf("anything")
        val seq = IoBufAsciiText(buf, 0, 0)
        assertEquals(0, seq.hashCode())
        buf.release()
    }

    @Test
    fun `contentEquals member overload matches stdlib semantics for String`() {
        val buf = bufOf("application/json")
        val seq = IoBufAsciiText(buf, 0, 16)
        assertTrue(seq.contentEquals("application/json"))
        assertTrue(!seq.contentEquals("application/jsonX"))
        assertTrue(!seq.contentEquals("application/jso"))
        assertTrue(!seq.contentEquals(""))
        buf.release()
    }

    @Test
    fun `contentEquals member overload matches stdlib semantics for CharSequence`() {
        val buf = bufOf("application/json")
        val seq = IoBufAsciiText(buf, 0, 16)
        val sb: CharSequence = StringBuilder("application/json")
        assertTrue(seq.contentEquals(sb))
        val other = IoBufAsciiText(buf, 0, 16)
        assertTrue(seq.contentEquals(other as CharSequence))
        buf.release()
    }

    @Test
    fun `contentEqualsAscii compares bytes byte-for-byte`() {
        val buf = bufOf("application/json")
        val seq = IoBufAsciiText(buf, 0, 16)
        assertTrue(seq.contentEqualsAscii("application/json".encodeToByteArray()))
        assertTrue(!seq.contentEqualsAscii("application/jsonX".encodeToByteArray()))
        assertTrue(!seq.contentEqualsAscii("application/jso".encodeToByteArray()))
        // empty
        val empty = IoBufAsciiText(buf, 0, 0)
        assertTrue(empty.contentEqualsAscii(ByteArray(0)))
        assertTrue(!empty.contentEqualsAscii("a".encodeToByteArray()))
        buf.release()
    }

    @Test
    fun `contentEqualsAscii is byte-equal and not case-insensitive`() {
        val buf = bufOf("Content-Type")
        val seq = IoBufAsciiText(buf, 0, 12)
        assertTrue(seq.contentEqualsAscii("Content-Type".encodeToByteArray()))
        // case mismatch must NOT match (caller is responsible for case
        // normalisation if they want case-insensitive compare)
        assertTrue(!seq.contentEqualsAscii("content-type".encodeToByteArray()))
        buf.release()
    }

    @Test
    fun `toString caches the materialised String across repeated calls`() {
        val buf = bufOf("Content-Type")
        try {
            val seq = IoBufAsciiText(buf, 0, 12)
            val first = seq.toString()
            val second = seq.toString()
            val third = seq.toString()
            // Reference identity: every subsequent toString must return
            // the exact same String instance the first call materialised,
            // not just an equal one. Catches a regression where the cache
            // is dropped and each call re-allocates.
            assertTrue(first === second, "second toString() did not hit the cache")
            assertTrue(second === third, "third toString() did not hit the cache")
            assertEquals("Content-Type", first)
        } finally {
            buf.release()
        }
    }

    @Test
    fun `toString cache does not alias across distinct views over the same range`() {
        val buf = bufOf("Content-Type")
        try {
            // Two distinct IoBufAsciiText instances over the same buffer
            // range. Cache lives on the instance, not the buffer / range,
            // so each view materialises its own content independently.
            // (We can only assert content equality portably — on JS,
            // `String` is a value type, so `===` is content equality, not
            // identity, and an instance-identity assertion would be a
            // JVM / Native semantic leaking into the test.)
            val a = IoBufAsciiText(buf, 0, 12)
            val b = IoBufAsciiText(buf, 0, 12)
            assertEquals("Content-Type", a.toString())
            assertEquals("Content-Type", b.toString())
            // Calling toString on one must not affect the other.
            assertEquals(a.toString(), b.toString())
        } finally {
            buf.release()
        }
    }

    @Test
    fun `start plus length must fit within buf capacity`() {
        val buf = bufOf("abc")
        // buf capacity is at least 3 (depends on allocator backing); the
        // strict guard is against passing a window past capacity.
        assertFailsWith<IllegalArgumentException> {
            IoBufAsciiText(buf, 0, buf.capacity + 1)
        }
        assertFailsWith<IllegalArgumentException> { IoBufAsciiText(buf, -1, 1) }
        assertFailsWith<IllegalArgumentException> { IoBufAsciiText(buf, 0, -1) }
        buf.release()
    }
}
