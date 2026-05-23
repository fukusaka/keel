package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Native cross-segment byte-op tests for [NativeIoBuf] in PR-2. Builds
 * a multi-seg buffer by constructing a primary segment via
 * [NativeIoBuf.overSegmentWithCap] (`maxCapacity > segment.capacity`)
 * and appending a second segment via [NativeIoBuf.appendSegment]. The
 * cases exercise the cross-seg slow path of each byte op — writes /
 * reads / random-access `getByte` / `copyTo` / `writeAscii` that
 * straddle the primary→extra boundary.
 */
@OptIn(ExperimentalForeignApi::class)
class NativeIoBufMultiSegTest {

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
        val extraOk = freshSegment(16)
        val extraOverflow = freshSegment(1)
        try {
            buf.appendSegment(extraOk)
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
            for (i in 0 until 8) buf.writeByte((0x41 + i).toByte())
            for (i in 0 until 8) {
                assertEquals((0x41 + i).toByte(), buf.readByte())
            }
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

    private fun newMultiSeg(primarySize: Int, maxCap: Int): NativeIoBuf {
        val ptr = nativeHeap.allocArray<ByteVar>(primarySize)
        val seg = Segment(NativeHeapBacking(ptr), primarySize)
        return NativeIoBuf.overSegmentWithCap(seg, HeapOwner, maxCap)
    }

    private fun freshSegment(capacity: Int): Segment {
        val ptr = nativeHeap.allocArray<ByteVar>(capacity)
        return Segment(NativeHeapBacking(ptr), capacity)
    }
}
