package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongPriorityQueueTest {
    @Test
    fun `poll returns values in ascending order`() {
        val q = LongPriorityQueue()
        listOf(5L, 1L, 3L, 2L, 4L).forEach { q.offer(it) }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList())
    }

    @Test
    fun `empty queue reports NO_VALUE`() {
        val q = LongPriorityQueue()
        assertTrue(q.isEmpty())
        assertEquals(LongPriorityQueue.NO_VALUE, q.poll())
        assertEquals(LongPriorityQueue.NO_VALUE, q.peek())
    }

    @Test
    fun `remove pulls an arbitrary element and keeps heap order`() {
        val q = LongPriorityQueue()
        listOf(10L, 20L, 30L, 40L, 50L).forEach { q.offer(it) }
        q.remove(30L)
        assertEquals(listOf(10L, 20L, 40L, 50L), generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList())
    }

    @Test
    fun `remove of a missing value is a no-op`() {
        val q = LongPriorityQueue()
        q.offer(1L)
        q.remove(99L)
        assertEquals(1L, q.poll())
    }

    /**
     * Removing the **last element** of the underlying heap array (the slot at
     * `array[size - 1]`) used to clear the slot to `0` and then `siftUp` on a
     * `0` value, percolating the zero up and putting it at `array[0]` (the
     * root). When the queue's sentinel coincided with that `0`, subsequent
     * `poll()` returned the sentinel even though the queue still held positive
     * elements, and for the `PoolChunk` `runsAvail` queues whose handles all
     * sit above zero that 0L propagated as a "free run" handle into
     * `PoolChunk.allocateRun`, producing a degenerate `PoolSubpage` whose
     * `bitmap` was empty and surfacing as an `ArrayIndexOutOfBoundsException`
     * at `PoolSubpage.free` on `PooledAllocator.close()`.
     */
    @Test
    fun `remove of the last element preserves heap order`() {
        val q = LongPriorityQueue()
        listOf(10L, 20L, 30L).forEach { q.offer(it) }
        // The highest leaf in a min-heap of size 3 is at index 2 (= size - 1).
        q.remove(30L)
        assertEquals(listOf(10L, 20L), generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList())
    }

    @Test
    fun `remove of a singleton leaves an empty queue`() {
        val q = LongPriorityQueue()
        q.offer(42L)
        q.remove(42L)
        assertTrue(q.isEmpty())
        assertEquals(LongPriorityQueue.NO_VALUE, q.poll())
    }

    /**
     * `remove()` of an interior element must call **both** `siftUp` and
     * `siftDown`: the value that replaces the matched slot — the queue's tail
     * — can be smaller than the matched slot's parent (in which case it has
     * to bubble up), or larger than the matched slot's children (in which case
     * it has to sink down). Netty's pre-2020 implementation called only the
     * sink half and broke the heap when the tail happened to be smaller than
     * the matched slot's ancestor (Netty PR #10832).
     *
     * This test exercises the `siftUp`-needed branch directly. Offering
     * `[5, 100, 10, 200, 150, 50, 20]` builds a min-heap with `20` at the
     * tail (index 6); removing `150` (index 4) replaces the slot with the
     * tail `20`. Because `20 < 100` (the matched slot's parent), the
     * replacement has to bubble up past index 1 — a pure `siftDown` would
     * leave the heap mis-ordered. The `poll()` sequence then has to come
     * back ascending.
     */
    @Test
    fun `remove of an interior element bubbles up the replacement when needed`() {
        val q = LongPriorityQueue()
        listOf(5L, 100L, 10L, 200L, 150L, 50L, 20L).forEach { q.offer(it) }
        q.remove(150L)
        assertEquals(
            listOf(5L, 10L, 20L, 50L, 100L, 200L),
            generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList(),
        )
    }

    /**
     * `NO_VALUE` is `-1L`, which sits outside the value space of stored handles
     * (positive `Long`s for `PoolChunk`). `offer` rejects the sentinel so it
     * can never be confused with a real entry, and `poll()` returns it only
     * for an empty queue — never for a non-empty one. The pre-fix
     * implementation used `0L` for the sentinel; with the Netty-style `remove`
     * shape that allows stale `0`s to live past `size` in transient states,
     * a `-1L` sentinel cleanly disambiguates "empty queue" from "queue holding
     * a `0` that should never exist" (the latter is rejected at `offer`).
     */
    @Test
    fun `offer rejects the NO_VALUE sentinel`() {
        val q = LongPriorityQueue()
        kotlin.test.assertFailsWith<IllegalArgumentException> { q.offer(LongPriorityQueue.NO_VALUE) }
    }

    @Test
    fun `peek does not remove`() {
        val q = LongPriorityQueue()
        q.offer(7L)
        assertEquals(7L, q.peek())
        assertEquals(7L, q.peek())
        assertEquals(7L, q.poll())
        assertTrue(q.isEmpty())
    }

    @Test
    fun `grows past initial capacity`() {
        val q = LongPriorityQueue()
        for (i in 100 downTo 1) q.offer(i.toLong())
        for (i in 1..100) assertEquals(i.toLong(), q.poll())
    }
}
