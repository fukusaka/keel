package io.github.fukusaka.keel.buf

/**
 * A binary min-heap of `Long` values, used by [PoolChunk] to hold the free
 * runs of one page-size class ordered so that the lowest-valued (lowest-offset)
 * run is allocated first.
 *
 * Modelled on Netty 4.2.12.Final's `io.netty.buffer.LongPriorityQueue`. The
 * stored values are run handles whose low bits are zero (a free run carries no
 * `bitmapIdx` and is not marked used), so ordering by the raw `Long` orders by
 * `(runOffset, runPages)`. [NO_VALUE] (`0`) is returned when the queue is empty;
 * a valid run handle is always positive (`runPages >= 1` sets a high bit), so
 * `0` is an unambiguous sentinel.
 *
 * Besides the usual [offer] / [poll] / [peek], the chunk needs [remove] of an
 * *arbitrary* element: when two adjacent free runs coalesce, the neighbour must
 * be pulled out of the middle of its queue. That is an O(n) scan plus an O(log n)
 * sift, which is fine — coalescing is off the hot path.
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

    /** Removes [value] if present (arbitrary-element removal). */
    fun remove(value: Long) {
        for (i in 0 until size) {
            if (array[i] == value) {
                size--
                array[i] = array[size]
                array[size] = 0
                // Restore heap order around the replacement: it may need to go
                // either up (smaller than parent) or down (larger than a child).
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
        /** Returned by [poll] / [peek] when the queue is empty. */
        const val NO_VALUE: Long = 0L
        private const val INITIAL_CAPACITY = 8
    }
}
