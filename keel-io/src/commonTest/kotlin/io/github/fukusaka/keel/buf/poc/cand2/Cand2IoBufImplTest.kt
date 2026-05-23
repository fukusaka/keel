package io.github.fukusaka.keel.buf.poc.cand2

import io.github.fukusaka.keel.buf.DefaultAllocator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Correctness smoke tests for [Cand2IoBufImpl] — mirrors
 * `Cand1IoBufImplTest` for the shared byte-level paths and adds tests
 * specific to the explicit [SegmentRangeList] surface (instance reuse,
 * range refresh on subsequent calls).
 */
class Cand2IoBufImplTest {

    private val segCap = 8

    @Test
    fun `single-segment write then read round-trips`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap)
        for (i in 0 until segCap) buf.writeByte(i.toByte())
        assertEquals(segCap, buf.writerIndex)
        for (i in 0 until segCap) assertEquals(i.toByte(), buf.readByte())
        assertEquals(0, buf.readableBytes)
        buf.close()
    }

    @Test
    fun `multi-segment write read across chain growth`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap)
        val total = segCap * 2 + 3
        for (i in 0 until total) buf.writeByte(i.toByte())
        assertTrue(buf.capacity >= total)
        for (i in 0 until total) assertEquals(i.toByte(), buf.readByte())
        buf.close()
    }

    @Test
    fun `getByte resolves across segments`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap)
        val total = segCap * 3
        for (i in 0 until total) buf.writeByte((i + 1).toByte())
        for (i in 0 until total) assertEquals((i + 1).toByte(), buf.getByte(i))
        buf.close()
    }

    @Test
    fun `readableSegments reflects current readerIndex writerIndex range`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap)
        for (i in 0 until segCap + 4) buf.writeByte(i.toByte())
        buf.readerIndex = 5
        val list = buf.readableSegments()
        var total = 0
        for (i in 0 until list.size) total += list[i].length
        assertEquals(buf.readableBytes, total)
        buf.close()
    }

    @Test
    fun `readableSegments returns the same list instance across calls - reuse`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap)
        for (i in 0 until 4) buf.writeByte(i.toByte())
        val first = buf.readableSegments()
        val second = buf.readableSegments()
        // Same list instance — caller-visible "no per-call allocation".
        assertSame(first, second)
        buf.close()
    }

    @Test
    fun `readableSegments returns the same SegmentRange instances across calls - reuse`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap)
        for (i in 0 until 4) buf.writeByte(i.toByte())
        val list1 = buf.readableSegments()
        val range1 = list1[0]
        // Write more (no chain growth — same single segment).
        for (i in 0 until 3) buf.writeByte(i.toByte())
        val list2 = buf.readableSegments()
        val range2 = list2[0]
        assertSame(range1, range2, "SegmentRange instance must be reused, only its fields rewritten")
        buf.close()
    }

    @Test
    fun `writableSegments reflects the unwritten tail`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap)
        for (i in 0 until 5) buf.writeByte(i.toByte())
        val list = buf.writableSegments()
        var total = 0
        for (i in 0 until list.size) total += list[i].length
        assertEquals(buf.writableBytes, total)
        buf.close()
    }
}
