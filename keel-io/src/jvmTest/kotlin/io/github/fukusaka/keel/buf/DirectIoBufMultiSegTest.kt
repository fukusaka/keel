package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

@OptIn(UnsafeIoBufApi::class)

/**
 * JVM cross-segment byte-op tests for [DirectIoBuf] in PR-2. Builds a
 * multi-seg buffer by constructing a primary segment via
 * [DirectIoBuf.overSegmentWithCap] (`maxCapacity > segment.capacity`)
 * and appending a second segment via [DirectIoBuf.appendSegment]. The
 * cases exercise the cross-seg slow path of each byte op — writes /
 * reads / random-access `getByte` / `copyTo` / `writeAscii` that
 * straddle the primary→extra boundary.
 */
class DirectIoBufMultiSegTest {

    @Test
    fun appendSegment_grows_capacity() {
        val buf = newMultiSeg(primarySize = 16, maxCap = 64)
        try {
            assertEquals(16, buf.capacity)
            buf.appendSegment(freshSegment(16))
            assertEquals(32, buf.capacity)
            buf.appendSegment(freshSegment(16))
            assertEquals(48, buf.capacity)
        } finally {
            buf.release()
        }
    }

    @Test
    fun appendSegment_at_cap_throws() {
        val buf = newMultiSeg(primarySize = 16, maxCap = 32)
        val extra = freshSegment(16)
        val extraOverflow = freshSegment(1)
        try {
            buf.appendSegment(extra)
            assertFailsWith<KeelBufferOverflowException> {
                buf.appendSegment(extraOverflow)
            }
        } finally {
            extraOverflow.release()
            buf.release()
        }
    }

    @Test
    fun writeByte_across_segment_boundary_reads_back_correctly() {
        val buf = newMultiSeg(primarySize = 4, maxCap = 16)
        buf.appendSegment(freshSegment(4))
        try {
            // Fill 4 bytes in primary, then 4 in extra.
            for (i in 0 until 8) buf.writeByte((0x41 + i).toByte())
            assertEquals(8, buf.writerIndex)
            assertEquals(8, buf.readableBytes)
            for (i in 0 until 8) {
                assertEquals((0x41 + i).toByte(), buf.readByte(), "byte at logical idx $i")
            }
            assertEquals(8, buf.readerIndex)
        } finally {
            buf.release()
        }
    }

    @Test
    fun writeByteArray_across_boundary_reads_back_correctly() {
        val buf = newMultiSeg(primarySize = 4, maxCap = 16)
        buf.appendSegment(freshSegment(8))
        try {
            val src = ByteArray(10) { (it * 3 + 1).toByte() }
            buf.writeByteArray(src, 0, 10)
            assertEquals(10, buf.writerIndex)
            val readBack = ByteArray(10)
            buf.readByteArray(readBack, 0, 10)
            assertEquals(src.toList(), readBack.toList())
        } finally {
            buf.release()
        }
    }

    @Test
    fun writeAscii_across_boundary_reads_back_correctly() {
        val buf = newMultiSeg(primarySize = 4, maxCap = 16)
        buf.appendSegment(freshSegment(8))
        try {
            buf.writeAscii("HelloMulti", 0, 10)
            val readBack = ByteArray(10)
            buf.readByteArray(readBack, 0, 10)
            assertEquals("HelloMulti", readBack.decodeToString())
        } finally {
            buf.release()
        }
    }

    @Test
    fun getByte_random_access_across_boundary() {
        val buf = newMultiSeg(primarySize = 4, maxCap = 16)
        buf.appendSegment(freshSegment(4))
        try {
            for (i in 0 until 8) buf.writeByte((0x30 + i).toByte())
            assertEquals(0x30.toByte(), buf.getByte(0))
            assertEquals(0x33.toByte(), buf.getByte(3))
            assertEquals(0x34.toByte(), buf.getByte(4))
            assertEquals(0x37.toByte(), buf.getByte(7))
        } finally {
            buf.release()
        }
    }

    @Test
    fun forEachReadableSegment_emits_multiple_windows() {
        val buf = newMultiSeg(primarySize = 4, maxCap = 16)
        buf.appendSegment(freshSegment(4))
        try {
            for (i in 0 until 6) buf.writeByte((0x40 + i).toByte())
            val lengths = mutableListOf<Int>()
            buf.forEachReadableSegment { _, _, len -> lengths += len }
            assertEquals(listOf(4, 2), lengths)
        } finally {
            buf.release()
        }
    }

    @Test
    fun fillReadableSegments_emits_multiple_windows() {
        val buf = newMultiSeg(primarySize = 4, maxCap = 16)
        buf.appendSegment(freshSegment(4))
        val list = SegmentRangeList()
        try {
            for (i in 0 until 6) buf.writeByte((0x40 + i).toByte())
            buf.fillReadableSegments(list)
            assertEquals(2, list.size)
            assertEquals(4, list[0].length)
            assertEquals(2, list[1].length)
        } finally {
            buf.release()
        }
    }

    @Test
    fun copyTo_across_source_boundary_into_single_seg_dest() {
        val src = newMultiSeg(primarySize = 4, maxCap = 16)
        src.appendSegment(freshSegment(4))
        val dest = createDefaultIoBuf(16)
        try {
            for (i in 0 until 8) src.writeByte((0x50 + i).toByte())
            src.copyTo(dest, 8)
            assertEquals(8, src.readerIndex)
            assertEquals(8, dest.writerIndex)
            val readBack = ByteArray(8)
            dest.readByteArray(readBack, 0, 8)
            for (i in 0 until 8) assertEquals((0x50 + i).toByte(), readBack[i])
        } finally {
            dest.release()
            src.release()
        }
    }

    @Test
    fun slice_window_inside_primary_works() {
        val buf = newMultiSeg(primarySize = 8, maxCap = 32)
        buf.appendSegment(freshSegment(8))
        try {
            buf.writeAscii("ABCDEFGH", 0, 8)
            val sliced = DefaultAllocator.slice(buf, 2, 4)
            try {
                val readBack = ByteArray(4)
                sliced.readByteArray(readBack, 0, 4)
                assertEquals("CDEF", readBack.decodeToString())
            } finally {
                sliced.release()
            }
        } finally {
            buf.release()
        }
    }

    @Test
    fun slice_across_segment_boundary_rejected() {
        val buf = newMultiSeg(primarySize = 4, maxCap = 16)
        buf.appendSegment(freshSegment(4))
        try {
            for (i in 0 until 8) buf.writeByte(0)
            assertFailsWith<IllegalArgumentException> {
                DefaultAllocator.slice(buf, 2, 4)
            }
        } finally {
            buf.release()
        }
    }

    @Test
    fun release_walks_chain_and_returns_true_when_all_reach_zero() {
        val buf = newMultiSeg(primarySize = 8, maxCap = 32)
        buf.appendSegment(freshSegment(8))
        // Single release path should drop both segments to zero.
        val released = buf.release()
        assertEquals(true, released)
    }

    @Test
    fun primary_property_is_stable_across_appends() {
        val buf = newMultiSeg(primarySize = 8, maxCap = 32)
        try {
            // We can't observe primary() directly via IoBuf API, but
            // the cached unsafeBuffer should remain backed by the same
            // primary throughout the chain's lifetime.
            val before = (buf as DirectIoBuf).unsafeBuffer
            buf.appendSegment(freshSegment(8))
            buf.appendSegment(freshSegment(8))
            val after = buf.unsafeBuffer
            assertSame(before, after, "primary ByteBuffer must not change on appendSegment")
        } finally {
            buf.release()
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────

    private fun newMultiSeg(primarySize: Int, maxCap: Int): DirectIoBuf {
        val seg = Segment(DirectByteBufferBacking(ByteBuffer.allocateDirect(primarySize)), primarySize)
        return DirectIoBuf.overSegmentWithCap(seg, HeapOwner, maxCap)
    }

    private fun freshSegment(capacity: Int): Segment =
        Segment(DirectByteBufferBacking(ByteBuffer.allocateDirect(capacity)), capacity)
}
