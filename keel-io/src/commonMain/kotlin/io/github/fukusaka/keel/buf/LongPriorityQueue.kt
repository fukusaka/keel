package io.github.fukusaka.keel.buf

/**
 * A binary min-heap of `Long` values, used by [PoolChunk] to hold the free
 * runs of one page-size class ordered so that the lowest-valued (lowest-offset)
 * run is allocated first.
 *
 * Modelled on the `io.netty.buffer.LongPriorityQueue` that ships with Netty
 * 4.1.x and the early 4.2.x line, kept in shape with Netty PR #10832 (the
 * Sedgewick & Wayne binary-heap reimplementation of `remove()` that fixed a
 * non-deterministic test failure on 2020-12-02) and *not* the metadata-shrunk
 * `IntPriorityQueue` rewrite (Netty PR #13504, 2023-07-25) that replaced this
 * class upstream from Netty 4.2.13 onward. keel keeps a `Long`-typed queue
 * because [PoolChunk] passes whole 64-bit handles through it; the high-32-bit
 * compaction that motivated the upstream rename is not adopted here.
 *
 * The stored values are run handles whose low bits are zero (a free run carries
 * no `bitmapIdx` and is not marked used), so ordering by the raw `Long` orders
 * by `(runOffset, runPages)`. [NO_VALUE] (`-1`) is returned by [poll] / [peek]
 * when the queue is empty; valid run handles are strictly positive (`runPages
 * >= 1` sets bit 34 in [PoolChunk]'s layout, with `runOffset` small enough that
 * the sign bit stays clear), so `-1` is an unambiguous sentinel and is disjoint
 * from any stored handle. The sentinel matches `PoolChunk.NO_HANDLE` so the
 * pool layer uses one consistent "no value" marker across both layers.
 *
 * Besides the usual [offer] / [poll] / [peek], the chunk needs [remove] of an
 * *arbitrary* element: when two adjacent free runs coalesce, the neighbour must
 * be pulled out of the middle of its queue. That is an O(n) scan plus an O(log n)
 * sift, which is fine — coalescing is off the hot path.
 *
 * **Algorithm shape.** Heap maintenance uses the **shift-cascade** form (save
 * the value once, shift parents/children through the path with a single write
 * per level, write the saved value at the final position). This is the form
 * Sedgewick & Wayne's Algorithms book recommends, and the one mainstream Java
 * heap implementations use — Lucene's `org.apache.lucene.util.PriorityQueue<T>`
 * and Solr's `org.apache.solr.util.LongPriorityQueue` (a same-named class but a
 * different bounded-top-K design) both ship the same shape verbatim. Netty's
 * `IntPriorityQueue` is the outlier: it uses a swap-based shape, presumably for
 * readability. Both are correct and equivalent in big-O terms; shift-cascade
 * halves the number of array writes when a value percolates multiple levels.
 * The observable contract (`offer` / `poll` / `peek` / `remove` / `isEmpty`,
 * including [NO_VALUE]'s value space) matches Netty's upstream queue exactly
 * regardless of the inner shape.
 *
 * **Indexing.** keel uses 0-indexed storage (root at `array[0]`) where Netty's
 * upstream is 1-indexed (root at `array[1]`, slot 0 unused). The choice is
 * cosmetic — Kotlin convention — and shows up only in the parent/child
 * arithmetic (`(i - 1) ushr 1`, `(i shl 1) + 1`) and in the growth expression
 * (plain doubling vs Netty's `1 + (length - 1) * 2` adjustment for the unused
 * slot).
 */
internal class LongPriorityQueue {
    private var array = LongArray(INITIAL_CAPACITY)
    private var size = 0

    /** Adds [value] to the queue. */
    fun offer(value: Long) {
        require(value != NO_VALUE) { "value $NO_VALUE is the sentinel and cannot be stored" }
        if (size >= array.size) {
            array = array.copyOf(array.size * 2)
        }
        array[size] = value
        siftUp(size)
        size++
    }

    /**
     * Removes [value] if present (arbitrary-element removal).
     *
     * Matches the Netty PR #10832 (2020-12-02) Sedgewick & Wayne shape: move
     * the last element to the matched slot, then run both siftUp and siftDown
     * to restore heap order at that position. The earlier in-place algorithm
     * had two latent traps: it only sifted down (missing the case where the
     * replacement is smaller than its new parent), and an unconditional
     * `array[size] = 0` clear of the vacated tail slot — which doubled as
     * blanking the matched slot when the match *was* the tail — caused
     * siftUp to percolate `0` (or [NO_VALUE], when the queue's sentinel
     * happened to coincide with the slot-clear constant) up to the root.
     * Both are gone here: no tail-clear is needed because stale data past
     * [size] is never read (siftUp/siftDown iterate within `0..size-1`, the
     * next [offer] overwrites the slot), and the tail-match case becomes a
     * no-op move + no-op sift naturally.
     */
    fun remove(value: Long) {
        for (i in 0 until size) {
            if (array[i] == value) {
                size--
                array[i] = array[size]
                siftUp(i)
                siftDown(i)
                return
            }
        }
    }

    /** Returns the smallest value without removing it, or [NO_VALUE] when empty. */
    fun peek(): Long = if (size == 0) NO_VALUE else array[0]

    /** Removes and returns the smallest value, or [NO_VALUE] when empty. */
    fun poll(): Long {
        if (size == 0) return NO_VALUE
        val root = array[0]
        size--
        array[0] = array[size]
        // Cosmetic zero-clear of the vacated slot — matches Netty's `poll`. The
        // slot now sits past `size` and is never read by siftUp/siftDown or by
        // a later [peek] (which short-circuits on `size == 0`); the next [offer]
        // would overwrite it anyway. Clearing keeps diagnostic dumps tidy and
        // does not depend on [NO_VALUE]'s numeric choice (`0` ≠ `-1L = NO_VALUE`,
        // so the cleared slot is *not* confusable with the sentinel).
        array[size] = 0
        siftDown(0)
        return root
    }

    fun isEmpty(): Boolean = size == 0

    private fun siftUp(start: Int) {
        var i = start
        val value = array[i]
        while (i > 0) {
            val parent = (i - 1) ushr 1
            if (array[parent] <= value) break
            array[i] = array[parent]
            i = parent
        }
        array[i] = value
    }

    private fun siftDown(start: Int) {
        var i = start
        val value = array[i]
        val half = size ushr 1
        while (i < half) {
            var child = (i shl 1) + 1
            val right = child + 1
            if (right < size && array[right] < array[child]) {
                child = right
            }
            if (array[child] >= value) break
            array[i] = array[child]
            i = child
        }
        array[i] = value
    }

    companion object {
        /**
         * Returned by [poll] / [peek] when the queue is empty, and rejected by
         * [offer]. Aligned with `PoolChunk.NO_HANDLE` so the two layers share
         * one "no value" sentinel; the negative value is also disjoint from the
         * value space of stored run handles (which are strictly positive), so a
         * stray `0` ever landing in the heap can be told apart from the empty
         * marker.
         */
        const val NO_VALUE: Long = -1L
        private const val INITIAL_CAPACITY = 8
    }
}
