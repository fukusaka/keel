package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [DefaultAllocator.slice] on Segment-backed buffers.
 *
 * Covers zero-copy sharing (the slice is a same-`Segment` window view),
 * independent reader/writer cursors, segment retention by the slice, the
 * [EmptyIoBuf] zero-length result, out-of-range rejection, and
 * slice-of-a-slice window-offset composition. These are pure synchronous
 * tests, so no timeout is needed.
 */
class IoBufSliceTest {

    private fun filled(capacity: Int): IoBuf {
        val buf = createDefaultIoBuf(capacity)
        for (i in 0 until capacity) {
            buf.writeByte(i.toByte())
        }
        return buf
    }

    @Test
    fun slice_has_own_indices_and_window() {
        val parent = filled(16)
        val s = DefaultAllocator.slice(parent, 4, 6)
        assertEquals(6, s.capacity)
        assertEquals(0, s.readerIndex)
        assertEquals(6, s.writerIndex)
        assertEquals(4.toByte(), s.getByte(0))
        assertEquals(9.toByte(), s.getByte(5))
        s.release()
        parent.release()
    }

    @Test
    fun slice_shares_backing_parent_write_visible_in_slice() {
        val parent = filled(16)
        val s = DefaultAllocator.slice(parent, 4, 4)
        // Overwrite byte at parent index 5 (slice index 1).
        parent.writerIndex = 5
        parent.writeByte(0x7F)
        assertEquals(0x7F.toByte(), s.getByte(1))
        s.release()
        parent.release()
    }

    @Test
    fun slice_shares_backing_slice_write_visible_in_parent() {
        val parent = filled(16)
        val s = DefaultAllocator.slice(parent, 8, 4)
        s.clear()
        s.writeByte(0x11)
        s.writeByte(0x22)
        assertEquals(0x11.toByte(), parent.getByte(8))
        assertEquals(0x22.toByte(), parent.getByte(9))
        s.release()
        parent.release()
    }

    @Test
    fun slice_retains_segment_until_slice_released() {
        val parent = filled(16)
        val s = DefaultAllocator.slice(parent, 2, 8)
        // Releasing the parent once does not free the backing — the slice
        // still holds a segment reference.
        assertFalse(parent.release())
        assertEquals(3.toByte(), s.getByte(1))
        // Releasing the slice drops the last reference.
        assertTrue(s.release())
    }

    @Test
    fun slice_zero_length_returns_EmptyIoBuf() {
        val parent = filled(8)
        assertSame(EmptyIoBuf, DefaultAllocator.slice(parent, 0, 0))
        assertSame(EmptyIoBuf, DefaultAllocator.slice(parent, 4, 0))
        parent.release()
    }

    @Test
    fun slice_out_of_range_throws() {
        val parent = filled(8)
        assertFailsWith<IllegalArgumentException> { DefaultAllocator.slice(parent, -1, 2) }
        assertFailsWith<IllegalArgumentException> { DefaultAllocator.slice(parent, 0, -1) }
        assertFailsWith<IllegalArgumentException> { DefaultAllocator.slice(parent, 4, 5) }
        assertFailsWith<IllegalArgumentException> { DefaultAllocator.slice(parent, 9, 1) }
        parent.release()
    }

    @Test
    fun slice_has_independent_reader_writer_indices() {
        val parent = filled(16)
        parent.readerIndex = 2
        parent.writerIndex = 14
        val s = DefaultAllocator.slice(parent, 4, 8)
        // Advancing the slice's cursors does not move the parent's.
        s.readByte()
        assertEquals(1, s.readerIndex)
        assertEquals(2, parent.readerIndex)
        s.clear()
        s.writeByte(0x33)
        assertEquals(1, s.writerIndex)
        assertEquals(14, parent.writerIndex)
        s.release()
        parent.release()
    }

    @Test
    fun slice_of_a_slice_composes_window_offsets() {
        val parent = filled(32)
        val outer = DefaultAllocator.slice(parent, 8, 16) // parent[8..24)
        val inner = DefaultAllocator.slice(outer, 4, 4) // outer[4..8) == parent[12..16)
        assertEquals(4, inner.capacity)
        assertEquals(12.toByte(), inner.getByte(0))
        assertEquals(15.toByte(), inner.getByte(3))
        // Write through the innermost view reaches the parent.
        inner.clear()
        inner.writeByte(0x09)
        assertEquals(0x09.toByte(), parent.getByte(12))
        inner.release()
        outer.release()
        parent.release()
    }
}
