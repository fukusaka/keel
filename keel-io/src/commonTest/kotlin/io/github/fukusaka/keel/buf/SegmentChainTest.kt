package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SegmentChainTest {

    @Test
    fun primary_only_chain_has_single_segment() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        assertEquals(1, chain.segmentCount)
        assertEquals(SEG, chain.totalCapacity)
        // releaseAll to drop the synthetic refcount we constructed with.
        chain.releaseAll()
    }

    @Test
    fun appendSegment_grows_total_capacity() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        chain.appendSegment(makeTestSegment(SEG))
        chain.appendSegment(makeTestSegment(SEG))
        assertEquals(3, chain.segmentCount)
        assertEquals(3 * SEG, chain.totalCapacity)
        chain.releaseAll()
    }

    @Test
    fun appendSegment_at_exact_cap_succeeds() {
        val chain = SegmentChain(makeTestSegment(SEG), 2 * SEG)
        chain.appendSegment(makeTestSegment(SEG))
        assertEquals(2 * SEG, chain.totalCapacity)
        chain.releaseAll()
    }

    @Test
    fun appendSegment_over_cap_throws() {
        val chain = SegmentChain(makeTestSegment(SEG), 2 * SEG)
        val tail = makeTestSegment(SEG + 1)
        assertFailsWith<KeelBufferOverflowException> {
            chain.appendSegment(tail)
        }
        // chain remains in a valid state — tail was not added.
        assertEquals(1, chain.segmentCount)
        assertEquals(SEG, chain.totalCapacity)
        // The rejected tail still has refcount 1 (caller still owns it).
        tail.release()
        chain.releaseAll()
    }

    @Test
    fun constructor_rejects_maxCapacity_below_primary() {
        val primary = makeTestSegment(SEG)
        assertFailsWith<IllegalArgumentException> {
            SegmentChain(primary, SEG - 1)
        }
        primary.release()
    }

    @Test
    fun forEachReadableSegment_walks_full_chain() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        chain.appendSegment(makeTestSegment(SEG))
        chain.appendSegment(makeTestSegment(SEG))

        val collected = mutableListOf<Triple<SegmentBacking, Int, Int>>()
        chain.forEachReadableSegment(readerIdx = 0, writerIdx = 3 * SEG) { mem, off, len ->
            collected += Triple(mem, off, len)
        }
        assertEquals(3, collected.size)
        // Every emitted range maps to a full segment window.
        for (t in collected) {
            assertEquals(0, t.second)
            assertEquals(SEG, t.third)
        }
        chain.releaseAll()
    }

    @Test
    fun forEachReadableSegment_clips_to_logical_window() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        chain.appendSegment(makeTestSegment(SEG))
        chain.appendSegment(makeTestSegment(SEG))

        // Window [SEG/2, 2*SEG + SEG/2) — clips the head and tail segments.
        val collected = mutableListOf<Triple<Int, Int, Int>>()
        chain.forEachReadableSegment(readerIdx = SEG / 2, writerIdx = 2 * SEG + SEG / 2) { _, off, len ->
            collected += Triple(collected.size, off, len)
        }
        assertEquals(3, collected.size)
        // Seg 0: offset SEG/2, length SEG/2
        assertEquals(SEG / 2, collected[0].second)
        assertEquals(SEG / 2, collected[0].third)
        // Seg 1: offset 0, length SEG
        assertEquals(0, collected[1].second)
        assertEquals(SEG, collected[1].third)
        // Seg 2: offset 0, length SEG/2
        assertEquals(0, collected[2].second)
        assertEquals(SEG / 2, collected[2].third)
        chain.releaseAll()
    }

    @Test
    fun forEachReadableSegment_empty_window_emits_nothing() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        var count = 0
        chain.forEachReadableSegment(readerIdx = 16, writerIdx = 16) { _, _, _ ->
            count++
        }
        assertEquals(0, count)
        chain.releaseAll()
    }

    @Test
    fun fillReadableSegments_populates_list() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        chain.appendSegment(makeTestSegment(SEG))
        val list = SegmentRangeList()
        chain.fillReadableSegments(readerIdx = 0, writerIdx = 2 * SEG, into = list)
        assertEquals(2, list.size)
        assertEquals(0, list[0].offset)
        assertEquals(SEG, list[0].length)
        assertNotNull(list[0].memory)
        assertEquals(0, list[1].offset)
        assertEquals(SEG, list[1].length)
        assertNotNull(list[1].memory)
        chain.releaseAll()
    }

    @Test
    fun fillReadableSegments_resets_list_between_calls() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        chain.appendSegment(makeTestSegment(SEG))
        chain.appendSegment(makeTestSegment(SEG))
        val list = SegmentRangeList()

        chain.fillReadableSegments(0, 3 * SEG, list)
        assertEquals(3, list.size)
        chain.fillReadableSegments(0, SEG, list)
        assertEquals(1, list.size)
        // Cleared slot references should be null.
        list[0].memory?.let { /* still present from refill */ }
        chain.releaseAll()
    }

    @Test
    fun fillReadableSegments_reuses_slot_instances() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        chain.appendSegment(makeTestSegment(SEG))
        val list = SegmentRangeList()
        chain.fillReadableSegments(0, 2 * SEG, list)
        val firstCallSlot0 = list[0]
        val firstCallSlot1 = list[1]

        chain.fillReadableSegments(0, 2 * SEG, list)
        assertSame(firstCallSlot0, list[0])
        assertSame(firstCallSlot1, list[1])
        chain.releaseAll()
    }

    @Test
    fun locateLogical_maps_to_segment_and_offset() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        chain.appendSegment(makeTestSegment(SEG))
        chain.appendSegment(makeTestSegment(SEG))

        val a = chain.locateLogical(0)
        assertEquals(0, unpackLocateSegmentIndex(a))
        assertEquals(0, unpackLocateLocalOffset(a))

        val b = chain.locateLogical(SEG - 1)
        assertEquals(0, unpackLocateSegmentIndex(b))
        assertEquals(SEG - 1, unpackLocateLocalOffset(b))

        val c = chain.locateLogical(SEG)
        assertEquals(1, unpackLocateSegmentIndex(c))
        assertEquals(0, unpackLocateLocalOffset(c))

        val d = chain.locateLogical(2 * SEG + 5)
        assertEquals(2, unpackLocateSegmentIndex(d))
        assertEquals(5, unpackLocateLocalOffset(d))

        chain.releaseAll()
    }

    @Test
    fun locateLogical_out_of_range_throws() {
        val chain = SegmentChain(makeTestSegment(SEG), MAX_CAP)
        assertFailsWith<IndexOutOfBoundsException> {
            chain.locateLogical(-1)
        }
        assertFailsWith<IndexOutOfBoundsException> {
            chain.locateLogical(SEG)
        }
        chain.releaseAll()
    }

    @Test
    fun retainAll_and_releaseAll_keep_refcounts_balanced() {
        val s0 = makeTestSegment(SEG)
        val s1 = makeTestSegment(SEG)
        val chain = SegmentChain(s0, MAX_CAP)
        chain.appendSegment(s1)

        // Each fresh segment starts with refCount=1; retainAll bumps to 2.
        chain.retainAll()
        // First releaseAll drops both to 1 — no segment reclaimed.
        val firstAnyReleased = chain.releaseAll()
        assertEquals(false, firstAnyReleased)
        // Second releaseAll drops both to 0 — both reclaimed.
        val secondAnyReleased = chain.releaseAll()
        assertTrue(secondAnyReleased)
    }

    companion object {
        private const val SEG: Int = 64
        private const val MAX_CAP: Int = 8 * SEG
    }
}
