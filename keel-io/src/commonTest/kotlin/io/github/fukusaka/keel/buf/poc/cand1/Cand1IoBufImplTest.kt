package io.github.fukusaka.keel.buf.poc.cand1

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.KeelBufferOverflowException
import io.github.fukusaka.keel.buf.SegmentBacking
import io.github.fukusaka.keel.buf.poc.extractSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Smoke + contract tests for [Cand1IoBufImpl].
 *
 * Mirrors the revised contract: writes target only already-chained
 * segments, [Cand1IoBuf.appendSegment] is the only growth path, and
 * both throw [KeelBufferOverflowException] on overflow with cap
 * enforced via the constructor's `maxCapacity` argument.
 */
class Cand1IoBufImplTest {

    private val segCap = 8
    private val maxCap = 4 * segCap // room for 4 segments by default

    @Test
    fun `single-segment write then read round-trips`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        for (i in 0 until segCap) buf.writeByte(i.toByte())
        assertEquals(segCap, buf.writerIndex)
        for (i in 0 until segCap) assertEquals(i.toByte(), buf.readByte())
        assertEquals(0, buf.readableBytes)
        buf.close()
    }

    @Test
    fun `explicit appendSegment grows the chain and preserves byte order`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        // Fill the primary segment exactly.
        for (i in 0 until segCap) buf.writeByte(i.toByte())
        // Explicit append — without this writeByte would throw.
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        // Continue writing into the new tail.
        for (i in segCap until segCap * 2) buf.writeByte(i.toByte())
        // Read back round-trips across the boundary.
        for (i in 0 until segCap * 2) assertEquals(i.toByte(), buf.readByte())
        buf.close()
    }

    @Test
    fun `writeByte throws KeelBufferOverflowException on tail full`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        for (i in 0 until segCap) buf.writeByte(i.toByte())
        // Tail full; no auto-grow.
        assertFailsWith<KeelBufferOverflowException> { buf.writeByte(0) }
        buf.close()
    }

    @Test
    fun `writeByteArray throws when length exceeds writableBytes`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        // segCap + 1 bytes into a segCap-only chain → must throw.
        val payload = ByteArray(segCap + 1)
        assertFailsWith<KeelBufferOverflowException> {
            buf.writeByteArray(payload, 0, payload.size)
        }
        buf.close()
    }

    @Test
    fun `appendSegment throws when growth would exceed maxCapacity`() {
        // maxCap = 2 * segCap; primary already gives 1 segCap, one
        // more append fits, the next must throw.
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, 2 * segCap)
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        assertEquals(2 * segCap, buf.capacity)
        assertFailsWith<KeelBufferOverflowException> {
            buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        }
        buf.close()
    }

    @Test
    fun `getByte resolves both segments after explicit append`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        for (i in 0 until segCap) buf.writeByte((i * 3).toByte())
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        for (i in segCap until segCap * 2) buf.writeByte((i * 3).toByte())
        for (i in 0 until segCap * 2) assertEquals((i * 3).toByte(), buf.getByte(i))
        buf.close()
    }

    @Test
    fun `bulk writeByteArray spans segment boundary after explicit grow`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        val payload = ByteArray(20) { it.toByte() }
        buf.writeByteArray(payload, 0, payload.size)
        val read = ByteArray(payload.size)
        buf.readByteArray(read, 0, payload.size)
        assertEquals(payload.toList(), read.toList())
        buf.close()
    }

    @Test
    fun `forEachReadableSegment iterates over the readable byte ranges only`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        for (i in 0 until segCap + 4) buf.writeByte(i.toByte())
        // Skip the first 3 bytes so the readable range starts mid-segment.
        buf.readerIndex = 3
        val captured = mutableListOf<Triple<SegmentBacking, Int, Int>>()
        buf.forEachReadableSegment { mem, off, len -> captured.add(Triple(mem, off, len)) }
        val totalCaptured = captured.sumOf { it.third }
        assertEquals(buf.readableBytes, totalCaptured)
        assertTrue(captured.size >= 1, "captured at least one segment range")
        buf.close()
    }

    @Test
    fun `forEachWritableSegment iterates over the writable tail`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        for (i in 0 until 5) buf.writeByte(i.toByte()) // 5/8 in primary
        val captured = mutableListOf<Triple<SegmentBacking, Int, Int>>()
        buf.forEachWritableSegment { mem, off, len -> captured.add(Triple(mem, off, len)) }
        val totalCaptured = captured.sumOf { it.third }
        assertEquals(buf.writableBytes, totalCaptured)
        buf.close()
    }
}
