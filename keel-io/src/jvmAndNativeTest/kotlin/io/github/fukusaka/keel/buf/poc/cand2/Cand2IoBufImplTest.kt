package io.github.fukusaka.keel.buf.poc.cand2

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.KeelBufferOverflowException
import io.github.fukusaka.keel.buf.poc.extractSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Smoke + contract tests for [Cand2IoBufImpl].
 *
 * Mirrors [Cand1IoBufImpl]'s contract — writes only target already-
 * chained segments, [Cand2IoBuf.appendSegment] is the only growth
 * path, both throw [KeelBufferOverflowException] on overflow — plus
 * the candidate-2 specific [Cand2IoBuf.readableSegments] /
 * [Cand2IoBuf.writableSegments] instance-reuse contract.
 */
class Cand2IoBufImplTest {

    private val segCap = 8
    private val maxCap = 4 * segCap

    @Test
    fun `single-segment write then read round-trips`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        for (i in 0 until segCap) buf.writeByte(i.toByte())
        for (i in 0 until segCap) assertEquals(i.toByte(), buf.readByte())
        assertEquals(0, buf.readableBytes)
        buf.close()
    }

    @Test
    fun `explicit appendSegment grows the chain and preserves byte order`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        for (i in 0 until segCap) buf.writeByte(i.toByte())
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        for (i in segCap until segCap * 3) buf.writeByte(i.toByte())
        for (i in 0 until segCap * 3) assertEquals(i.toByte(), buf.readByte())
        buf.close()
    }

    @Test
    fun `writeByte throws KeelBufferOverflowException on tail full`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        for (i in 0 until segCap) buf.writeByte(i.toByte())
        assertFailsWith<KeelBufferOverflowException> { buf.writeByte(0) }
        buf.close()
    }

    @Test
    fun `appendSegment throws when growth would exceed maxCapacity`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, 2 * segCap)
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        assertFailsWith<KeelBufferOverflowException> {
            buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        }
        buf.close()
    }

    @Test
    fun `getByte resolves across segments after explicit append`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        val total = segCap * 3
        for (i in 0 until total) buf.writeByte((i + 1).toByte())
        for (i in 0 until total) assertEquals((i + 1).toByte(), buf.getByte(i))
        buf.close()
    }

    @Test
    fun `readableSegments reflects current readerIndex writerIndex range`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
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
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        for (i in 0 until 4) buf.writeByte(i.toByte())
        val first = buf.readableSegments()
        val second = buf.readableSegments()
        assertSame(first, second)
        buf.close()
    }

    @Test
    fun `readableSegments returns the same SegmentRange instances across calls - reuse`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        for (i in 0 until 4) buf.writeByte(i.toByte())
        val list1 = buf.readableSegments()
        val range1 = list1[0]
        for (i in 0 until 3) buf.writeByte(i.toByte())
        val list2 = buf.readableSegments()
        val range2 = list2[0]
        assertSame(range1, range2, "SegmentRange instance must be reused, only its fields rewritten")
        buf.close()
    }

    @Test
    fun `writableSegments reflects the unwritten tail`() {
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        for (i in 0 until 5) buf.writeByte(i.toByte())
        val list = buf.writableSegments()
        var total = 0
        for (i in 0 until list.size) total += list[i].length
        assertEquals(buf.writableBytes, total)
        buf.close()
    }
}
