package io.github.fukusaka.keel.buf

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LongPriorityQueueTest {
    @Test
    fun `poll returns values in ascending order`() {
        val q = LongPriorityQueue()
        listOf(5L, 1L, 3L, 2L, 4L).forEach { q.offer(it) }
        assertEquals(
            listOf(1L, 2L, 3L, 4L, 5L),
            generateSequence {
                q.poll()
            }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList(),
        )
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
        assertEquals(
            listOf(10L, 20L, 40L, 50L),
            generateSequence {
                q.poll()
            }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList(),
        )
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
        assertEquals(
            listOf(10L, 20L),
            generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList(),
        )
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

    /**
     * `NO_VALUE` is part of the queue's wire contract: callers (notably
     * `PoolChunk.allocateRun`) rely on its numeric identity to distinguish
     * "empty" from a stored handle, and `PoolChunk.NO_HANDLE` is held at the
     * same `-1L` so the two layers share one "no value" marker. Pin the
     * numeric value rather than just the empty-return contract so a future
     * change of the constant (back to `0L`, or to anything else that overlaps
     * the value space) is caught at the source.
     */
    @Test
    fun `NO_VALUE has the documented numeric value`() {
        assertEquals(-1L, LongPriorityQueue.NO_VALUE)
    }

    /**
     * Removing the **root** (the smallest element) replaces it with the heap's
     * tail and runs `siftDown` to restore order — the symmetric case to the
     * `remove of the last element preserves heap order` test (which exercises
     * `array[i] = array[size]` with `i == size` so the move is a no-op). Root
     * removal is the position-zero counterpart and exercises a full siftDown
     * from the root through both child branches.
     */
    @Test
    fun `remove of the root sinks the replacement and preserves heap order`() {
        val q = LongPriorityQueue()
        // Build a 7-element min-heap: [5, 10, 20, 30, 40, 50, 60] in offer order.
        listOf(5L, 10L, 20L, 30L, 40L, 50L, 60L).forEach { q.offer(it) }
        q.remove(5L) // remove the root
        assertEquals(
            listOf(10L, 20L, 30L, 40L, 50L, 60L),
            generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList(),
        )
    }

    /**
     * Companion to `remove of an interior element bubbles up the replacement
     * when needed`: this case exercises the **siftDown** half of the
     * `siftUp(i) + siftDown(i)` pair in `remove`. The matched slot's
     * replacement (the heap's tail) is **larger** than at least one of the
     * matched slot's children, so a pure `siftUp` would leave the heap
     * mis-ordered; `siftDown` has to sink it.
     *
     * Offering `[1, 2, 50, 3, 4, 60, 70]` builds a min-heap whose layout puts
     * `70` at the tail (index 6). Removing `2` (index 1, near the root) puts
     * `70` at index 1. `70 > 1` (parent) so `siftUp` is a no-op, but
     * `70 > 3 = array[3]` (child of index 1), so `siftDown` has to walk down.
     */
    @Test
    fun `remove of an interior element sinks the replacement when needed`() {
        val q = LongPriorityQueue()
        listOf(1L, 2L, 50L, 3L, 4L, 60L, 70L).forEach { q.offer(it) }
        q.remove(2L)
        assertEquals(
            listOf(1L, 3L, 4L, 50L, 60L, 70L),
            generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList(),
        )
    }

    /**
     * After `remove()` the trailing slot at `array[size]` is left with stale
     * data (the Netty-style algorithm deliberately skips the slot-clear; see
     * the `LongPriorityQueue.remove` KDoc). The next `offer` must overwrite
     * that slot cleanly — if `size` and `array[size]` ever drift out of sync,
     * the freshly-offered value would land at the wrong index and break heap
     * order. Round-trip: build a heap, remove an element, offer a fresh value
     * that should re-occupy the tail slot, and assert the full poll order.
     */
    @Test
    fun `offer after remove cleanly re-uses the tail slot`() {
        val q = LongPriorityQueue()
        listOf(1L, 2L, 3L).forEach { q.offer(it) }
        q.remove(3L) // tail removal — `array[2]` is now stale (= 3L) but `size = 2`
        q.offer(5L) // must land at array[2], overwriting the stale 3L
        assertEquals(
            listOf(1L, 2L, 5L),
            generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList(),
        )
    }

    /**
     * `peek()` after `remove()` must reflect the new root, not a cached or
     * stale value. The other tests reach the post-remove state through
     * `poll()`, which exercises a different read path (root extract + sink)
     * than `peek()` (root read only). Pin `peek` independently so a future
     * regression that breaks one read path without breaking the other is
     * caught.
     */
    @Test
    fun `peek reflects the new root after remove`() {
        val q = LongPriorityQueue()
        listOf(10L, 20L, 30L).forEach { q.offer(it) }
        q.remove(10L) // remove root → new root must be 20L
        assertEquals(20L, q.peek())
        // peek is non-destructive — repeated reads stay consistent.
        assertEquals(20L, q.peek())
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

    /**
     * `remove(value)` is a linear scan that returns on the **first** match, so
     * a queue holding the same value twice loses only one occurrence per call.
     * Pinning this matters because a future change to `removeAll`-style
     * semantics would be a silent contract break — `PoolChunk` does not insert
     * duplicates today, but the queue is an internal primitive whose
     * contract may be reused.
     */
    @Test
    fun `remove of a duplicate value removes only one occurrence`() {
        val q = LongPriorityQueue()
        listOf(10L, 20L, 10L, 30L).forEach { q.offer(it) }
        q.remove(10L)
        assertEquals(
            // One 10 remains; the rest are intact and sorted.
            listOf(10L, 20L, 30L),
            generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList(),
        )
    }

    /**
     * `offer` accepts any `Long` value except [LongPriorityQueue.NO_VALUE]
     * (`-1L`). The queue is an internal primitive — `PoolChunk` only stores
     * positive run handles, but the contract surface is broader, and a future
     * caller could reasonably offer `0L`, [Long.MAX_VALUE], [Long.MIN_VALUE],
     * or any other negative value. Pin that none of these confuse the heap
     * (in particular, no aliasing with the sentinel) and that they poll back
     * in ascending `Long` order.
     */
    @Test
    fun `offer accepts any Long value except NO_VALUE`() {
        val q = LongPriorityQueue()
        // Mix of values around the contract boundary: most negative, just above
        // the sentinel, zero (which used to BE the sentinel), small positives,
        // and the largest representable `Long`.
        val values = listOf(Long.MIN_VALUE, -100L, 0L, 1L, 100L, Long.MAX_VALUE)
        values.forEach { q.offer(it) }
        // Ascending order = sorted natural `Long` order, which is what the
        // min-heap delivers; verifies the sift comparisons hold across the
        // full `Long` range (no overflow or sign-bit confusion).
        assertEquals(
            values.sorted(),
            generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList(),
        )
    }

    /**
     * `0L` deserves a separate sanity test: it used to be the sentinel itself,
     * and the previous bug propagated as a literal `0L` masquerading as the
     * empty marker. After the sentinel moved to `-1L`, `0L` is a perfectly
     * legal stored value — offering it must not confuse `poll` / `peek` /
     * `isEmpty`.
     */
    @Test
    fun `zero is a legal stored value after the sentinel moved to -1L`() {
        val q = LongPriorityQueue()
        q.offer(0L)
        assertFalse(q.isEmpty())
        assertEquals(0L, q.peek())
        assertEquals(0L, q.poll())
        assertTrue(q.isEmpty())
        assertEquals(LongPriorityQueue.NO_VALUE, q.poll())
    }

    /**
     * Cross-product state-coherence: an arbitrary interleaving of `offer`,
     * `poll`, `remove`, and `peek` must keep `size` and the heap in sync.
     * Drives the queue through every API in mixed order and verifies state
     * at each step against a reference `sorted-list` oracle. Catches the
     * class of bug where one method silently violates an invariant that
     * a different method then trusts (the way the previous regression went
     * undetected by single-method tests until `engine.close()` happened to
     * drain a corrupted freelist).
     */
    @Test
    fun `interleaved offer poll remove keeps the queue consistent with a sorted-list oracle`() {
        val q = LongPriorityQueue()
        // A mutable sorted view used as the truth source. After every queue
        // operation, the smallest value of `oracle` must equal `q.peek()`,
        // and `oracle.isEmpty()` must match `q.isEmpty()`.
        val oracle = mutableListOf<Long>()

        fun expect() {
            assertEquals(oracle.isEmpty(), q.isEmpty(), "isEmpty drift")
            if (oracle.isEmpty()) {
                assertEquals(LongPriorityQueue.NO_VALUE, q.peek(), "peek on empty")
            } else {
                assertEquals(oracle.min(), q.peek(), "peek root drift")
            }
        }

        fun offer(v: Long) {
            q.offer(v)
            oracle.add(v)
            expect()
        }

        fun poll() {
            val expected = oracle.min()
            oracle.remove(expected)
            assertEquals(expected, q.poll(), "poll value drift")
            expect()
        }

        fun remove(v: Long) {
            val present = oracle.remove(v) // remove first occurrence
            q.remove(v)
            if (!present) {
                // Removing a missing value must be a no-op; oracle unchanged.
                expect()
            } else {
                expect()
            }
        }

        // Interleaving exercise: build up, drain partly, add more around
        // remove'd holes, drain to empty.
        offer(5L)
        offer(1L)
        offer(10L) // [1, 5, 10]
        poll() // [5, 10]
        offer(3L)
        offer(8L) // [3, 5, 8, 10]
        remove(5L) // [3, 8, 10]
        remove(99L) // missing → no-op
        offer(2L)
        offer(4L) // [2, 3, 4, 8, 10]
        poll()
        poll() // [4, 8, 10]
        remove(8L) // [4, 10]
        offer(7L)
        offer(1L) // [1, 4, 7, 10]
        while (!oracle.isEmpty()) poll() // drain
        assertTrue(q.isEmpty(), "queue not empty after oracle drained")
        assertEquals(LongPriorityQueue.NO_VALUE, q.poll(), "poll on drained queue")
    }

    /**
     * Randomised broad correctness net. Generates a deterministic sequence of
     * `N` `Long` values from a seeded PRNG, offers all of them, then polls
     * the queue until empty and asserts the popped sequence matches the
     * input sorted ascending. Exercises sift orderings the hand-written
     * tests would not enumerate. Seeded so failures are reproducible from
     * the test name alone.
     */
    @Test
    fun `randomised offer-then-poll yields sorted output`() {
        val q = LongPriorityQueue()
        val n = 1_000
        // Fixed seed → deterministic test; bump on intentional regen.
        val rng = Random(seed = 0xC0FFEE5EEDL)
        val input = LongArray(n) {
            // Generate any `Long` in the valid value space (everything except
            // the sentinel). Reject-resample is fine — collisions are vanishingly
            // rare and the loop terminates almost always on the first draw.
            var v: Long
            do { v = rng.nextLong() } while (v == LongPriorityQueue.NO_VALUE)
            v
        }
        input.forEach { q.offer(it) }
        val expected = input.toList().sorted()
        val actual = buildList(n) {
            repeat(n) { add(q.poll()) }
        }
        assertEquals(expected, actual, "poll order does not match sorted input")
        assertTrue(q.isEmpty(), "queue not drained after $n polls")
        assertEquals(LongPriorityQueue.NO_VALUE, q.poll(), "poll on drained queue")
    }
}
