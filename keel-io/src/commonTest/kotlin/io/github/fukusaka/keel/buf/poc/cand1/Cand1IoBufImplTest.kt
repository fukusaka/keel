package io.github.fukusaka.keel.buf.poc.cand1

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.SegmentBacking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Minimal correctness smoke tests for [Cand1IoBufImpl] — single-seg
 * fast path, append-on-fill triggering a new segment, multi-seg read /
 * write byte symmetry, and the callback-shaped segment iteration.
 *
 * These are NOT performance tests (those live in the PoC microbench
 * once phase 6 lands). They exist to catch the obvious bugs early so
 * the microbench's results reflect a correct implementation.
 */
class Cand1IoBufImplTest {

    private val segCap = 8

    @Test
    fun `single-segment write then read round-trips`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap)
        for (i in 0 until segCap) buf.writeByte(i.toByte())
        assertEquals(segCap, buf.writerIndex)
        assertEquals(segCap, buf.readableBytes)
        for (i in 0 until segCap) assertEquals(i.toByte(), buf.readByte())
        assertEquals(0, buf.readableBytes)
        buf.close()
    }

    @Test
    fun `append-on-fill grows segments and preserves byte order across the boundary`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap)
        // Write 1.5 segments worth so the chain grows to 2 segments.
        val total = segCap + (segCap / 2)
        for (i in 0 until total) buf.writeByte(i.toByte())
        assertEquals(total, buf.writerIndex)
        assertTrue(buf.capacity >= total, "capacity must grow to fit")
        for (i in 0 until total) assertEquals(i.toByte(), buf.readByte())
        buf.close()
    }

    @Test
    fun `getByte resolves both segments after chain grows`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap)
        val total = segCap * 2
        for (i in 0 until total) buf.writeByte((i * 3).toByte())
        for (i in 0 until total) assertEquals((i * 3).toByte(), buf.getByte(i))
        buf.close()
    }

    @Test
    fun `bulk writeByteArray spans segment boundary`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap)
        val payload = ByteArray(20) { it.toByte() }
        buf.writeByteArray(payload, 0, payload.size)
        val read = ByteArray(payload.size)
        buf.readByteArray(read, 0, payload.size)
        assertEquals(payload.toList(), read.toList())
        buf.close()
    }

    @Test
    fun `forEachReadableSegment iterates over the readable byte ranges only`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap)
        for (i in 0 until segCap + 4) buf.writeByte(i.toByte())
        // Skip the first 3 bytes so the readable range starts mid-segment.
        buf.readerIndex = 3
        val captured = mutableListOf<Triple<SegmentBacking, Int, Int>>()
        buf.forEachReadableSegment { mem, off, len -> captured.add(Triple(mem, off, len)) }
        val totalCaptured = captured.sumOf { it.third }
        assertEquals(buf.readableBytes, totalCaptured)
        buf.close()
    }

    @Test
    fun `forEachWritableSegment iterates over the writable tail`() {
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap)
        for (i in 0 until 5) buf.writeByte(i.toByte()) // 5/8 written in segment 0
        val captured = mutableListOf<Triple<SegmentBacking, Int, Int>>()
        buf.forEachWritableSegment { mem, off, len -> captured.add(Triple(mem, off, len)) }
        val totalCaptured = captured.sumOf { it.third }
        assertEquals(buf.writableBytes, totalCaptured)
        buf.close()
    }
}
