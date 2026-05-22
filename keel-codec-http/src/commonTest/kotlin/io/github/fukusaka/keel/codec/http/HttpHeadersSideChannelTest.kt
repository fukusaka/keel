package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Correctness of the **IntArray-slot string side-channel** (L7-a-ii
 * Variant Y): range entries (byte-range views over a retained recv
 * buffer) coexisting with string entries (`add` / `set` / cross-read
 * fallback, stored in `stringBacking` via the `STRING_SENTINEL` slot),
 * plus the recv-buffer retain/release lifecycle.
 *
 * The existing [HttpHeadersTest] covers the all-string path
 * (`add` / `of`); these tests cover what is new in Variant Y: the
 * `addRange` parse path, range/string mixing, the cross-buffer
 * materialisation fallback, and pooled-reuse state reset.
 */
class HttpHeadersSideChannelTest {

    // Packs `name + value` contiguously into a fresh buffer (refCount 1).
    private fun bufOf(name: String, value: String): IoBuf {
        val bytes = (name + value).encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    private fun HttpHeaders.addRangeOf(buf: IoBuf, name: String, value: String): HttpHeaders {
        val hash = HttpHeaders.caseInsensitiveHashOfBuf(buf, 0, name.length)
        return addRange(buf, hash, 0, name.length, name.length, value.length)
    }

    @Test
    fun `range entry is readable as view and materialised string`() {
        val buf = bufOf("Host", "example.com")
        val h = HttpHeaders.borrow()
        h.addRangeOf(buf, "Host", "example.com")

        // get returns a CharSequence view; getString materialises.
        assertEquals("example.com", h["Host"]?.toString())
        assertEquals("example.com", h.getString("host")) // case-insensitive
        assertTrue("Host" in h)
        assertEquals(1, h.size)
        assertEquals("Host", h.nameAt(0)) // original wire case preserved

        h.release()
        assertTrue(buf.release()) // exactly one extra retain was balanced
    }

    @Test
    fun `range and string entries coexist`() {
        val buf = bufOf("Host", "example.com")
        val h = HttpHeaders.borrow()
        h.addRangeOf(buf, "Host", "example.com") // range entry
        h.add("X-Trace", "abc123") // string entry

        assertEquals("example.com", h.getString("Host"))
        assertEquals("abc123", h.getString("X-Trace"))
        assertEquals(2, h.size)

        val seen = mutableMapOf<String, String>()
        h.forEach { n, v -> seen[n] = v }
        assertEquals(mapOf("Host" to "example.com", "X-Trace" to "abc123"), seen)
        assertEquals(setOf("Host", "X-Trace"), h.names())

        h.release()
        assertTrue(buf.release())
    }

    @Test
    fun `set overwrites a range entry with a string value`() {
        val buf = bufOf("Content-Type", "text/html")
        val h = HttpHeaders.borrow()
        h.addRangeOf(buf, "Content-Type", "text/html")

        h["Content-Type"] = "application/json" // set -> removeAll + add (string)

        assertEquals("application/json", h.getString("Content-Type"))
        assertEquals(1, h.size)

        h.release()
        assertTrue(buf.release())
    }

    @Test
    fun `removeAll rebuild keeps surviving range and string entries`() {
        val buf = bufOf("Host", "example.com")
        val h = HttpHeaders.borrow()
        h.addRangeOf(buf, "Host", "example.com") // range, kept
        h.add("X-A", "1") // string, removed
        h.add("X-B", "2") // string, kept

        h.remove("X-A")

        assertEquals(2, h.size)
        assertEquals("example.com", h.getString("Host")) // range survives rebuild
        assertEquals("2", h.getString("X-B")) // string survives + reindexed
        assertNull(h.getString("X-A"))
        assertEquals(listOf("Host" to "example.com", "X-B" to "2"), h.entries())

        h.release()
        assertTrue(buf.release())
    }

    @Test
    fun `addRange with a different buffer materialises as string and retains only the first`() {
        val bufA = bufOf("Host", "example.com")
        val bufB = bufOf("Accept", "text/*")
        val h = HttpHeaders.borrow()
        h.addRangeOf(bufA, "Host", "example.com") // backing = A, A retained
        h.addRangeOf(bufB, "Accept", "text/*") // cur(A) !== B -> materialise via add()

        assertEquals("example.com", h.getString("Host")) // view over A
        assertEquals("text/*", h.getString("Accept")) // string copy of B
        assertEquals(2, h.size)

        h.release() // releases A only
        assertTrue(bufA.release()) // A was retained once -> freed here
        assertTrue(bufB.release()) // B never retained by headers -> freed here
    }

    @Test
    fun `pooled reuse resets backing and string side-channel`() {
        val buf = bufOf("Host", "example.com")
        val h1 = HttpHeaders.borrow()
        h1.addRangeOf(buf, "Host", "example.com")
        h1.add("X-A", "1")
        h1.release() // resetForReuse: clears slots + stringBacking, releases buf
        assertTrue(buf.release())

        // Borrow again — should be fresh, no stale range/string state.
        val h2 = HttpHeaders.borrow()
        assertTrue(h2.isEmpty)
        assertNull(h2.getString("Host"))
        assertNull(h2.getString("X-A"))
        assertFalse("Host" in h2)

        h2.add("X-New", "v") // string entry on the reused instance
        assertEquals("v", h2.getString("X-New"))
        assertEquals(1, h2.size)
        h2.release()
    }
}
