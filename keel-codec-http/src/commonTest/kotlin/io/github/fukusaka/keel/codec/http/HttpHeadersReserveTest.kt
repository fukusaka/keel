package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [HttpHeaders.build] with an entry-count hint (and the underlying
 * [HttpHeaders.reserve] pre-sizing) must build a header set
 * indistinguishable from the un-hinted [HttpHeaders.build] — the hint only
 * changes the initial storage size, never the observable contents.
 */
class HttpHeadersReserveTest {

    @Test
    fun `hinted build matches unhinted build for the same headers`() {
        val hinted = HttpHeaders.build(2) {
            add("Content-Type", "text/plain")
            add("Content-Length", "13")
        }
        val plain = HttpHeaders.build {
            add("Content-Type", "text/plain")
            add("Content-Length", "13")
        }
        assertEquals(plain, hinted)
        assertEquals(plain.hashCode(), hinted.hashCode())
        assertEquals(2, hinted.size)
        assertEquals("text/plain", hinted["content-type"])
        assertEquals("13", hinted["Content-Length"])
        assertEquals(plain.entries(), hinted.entries())
    }

    @Test
    fun `adding more than the hint grows and keeps every entry`() {
        val h = HttpHeaders.build(2) {
            add("A", "1")
            add("B", "2")
            add("C", "3")
            add("D", "4")
            add("E", "5")
        }
        assertEquals(5, h.size)
        assertEquals("1", h["a"])
        assertEquals("5", h["e"])
        assertEquals(listOf("A" to "1", "B" to "2", "C" to "3", "D" to "4", "E" to "5"), h.entries())
    }

    @Test
    fun `hint larger than the actual count leaves no phantom entries`() {
        val h = HttpHeaders.build(8) {
            add("Only", "one")
        }
        assertEquals(1, h.size)
        assertEquals("one", h["only"])
        assertNull(h["missing"])
    }

    @Test
    fun `hint of zero behaves like an empty build`() {
        val h = HttpHeaders.build(0) {}
        assertTrue(h.isEmpty)
        assertEquals(0, h.size)
        assertEquals(HttpHeaders.EMPTY, h)
        assertEquals(HttpHeaders.EMPTY.hashCode(), h.hashCode())
    }

    @Test
    fun `negative hint is ignored and the build still works`() {
        val h = HttpHeaders.build(-4) {
            add("Content-Type", "text/plain")
        }
        assertEquals(1, h.size)
        assertEquals("text/plain", h["content-type"])
    }

    @Test
    fun `overflowing hint falls back to lazy growth instead of throwing`() {
        // entries * STRIDE would overflow Int and wrap to a negative IntArray
        // size; the advisory hint must be ignored, not throw.
        val h = HttpHeaders.build(Int.MAX_VALUE) {
            add("Content-Type", "text/plain")
            add("Content-Length", "0")
        }
        assertEquals(2, h.size)
        assertEquals("text/plain", h["content-type"])
        assertEquals("0", h["content-length"])
    }

    @Test
    fun `hinted build with no additions is empty`() {
        val h = HttpHeaders.build(4) {}
        assertTrue(h.isEmpty)
        assertNull(h["anything"])
    }

    @Test
    fun `of pre-sizes and preserves every pair`() {
        val h = HttpHeaders.of("Content-Type" to "text/plain", "Content-Length" to "0", "X-Trace" to "abc")
        assertEquals(3, h.size)
        assertEquals("text/plain", h["content-type"])
        assertEquals("0", h["content-length"])
        assertEquals("abc", h["x-trace"])
    }

    @Test
    fun `empty of allocates no phantom entries`() {
        val h = HttpHeaders.of()
        assertTrue(h.isEmpty)
        assertEquals(HttpHeaders.EMPTY, h)
    }
}
