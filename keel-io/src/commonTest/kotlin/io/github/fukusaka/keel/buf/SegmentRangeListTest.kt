package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class SegmentRangeListTest {

    @Test
    fun new_list_starts_empty() {
        val list = SegmentRangeList()
        assertEquals(0, list.size)
    }

    @Test
    fun acquireSlot_returns_distinct_pre_allocated_cells() {
        val list = SegmentRangeList()
        val slots = Array(INITIAL_CAPACITY) { list.acquireSlot() }
        assertEquals(INITIAL_CAPACITY, list.size)
        // Each slot is a distinct instance.
        for (i in 0 until INITIAL_CAPACITY) {
            for (j in i + 1 until INITIAL_CAPACITY) {
                assertNotSame(slots[i], slots[j])
            }
        }
    }

    @Test
    fun acquireSlot_grows_beyond_initial_capacity() {
        val list = SegmentRangeList()
        repeat(INITIAL_CAPACITY * 3) { list.acquireSlot() }
        assertEquals(INITIAL_CAPACITY * 3, list.size)
    }

    @Test
    fun reset_clears_size_and_nullifies_memory_refs() {
        val list = SegmentRangeList()
        val r = list.acquireSlot()
        r.set(FakeSegmentBacking, offset = 4, length = 8)
        assertNotNull(list[0].memory)

        list.clear()
        assertEquals(0, list.size)

        // After reset, the underlying slot still exists but its memory ref is cleared.
        val r2 = list.acquireSlot()
        assertSame(r, r2)
        assertNull(r2.memory)
        assertEquals(0, r2.offset)
        assertEquals(0, r2.length)
    }

    @Test
    fun reset_then_refill_reuses_same_slot_instances() {
        val list = SegmentRangeList()
        val first = list.acquireSlot()
        val second = list.acquireSlot()
        list.clear()
        assertSame(first, list.acquireSlot())
        assertSame(second, list.acquireSlot())
    }

    @Test
    fun get_out_of_range_throws() {
        val list = SegmentRangeList()
        assertFailsWith<IndexOutOfBoundsException> { list[0] }
        list.acquireSlot()
        assertFailsWith<IndexOutOfBoundsException> { list[-1] }
        assertFailsWith<IndexOutOfBoundsException> { list[1] }
    }

    companion object {
        private const val INITIAL_CAPACITY: Int = 8
    }
}
