package io.github.fukusaka.keel.buf

/**
 * Mutable cell describing a contiguous window into one [Segment]'s
 * [SegmentBacking] — `[offset, offset + length)` in the backing's local
 * coordinate space.
 *
 * Instances are produced by [SegmentRangeList] and re-used across calls
 * to avoid per-iteration allocation. Engines consume the trio
 * `(memory, offset, length)` to build scatter-gather descriptors
 * (`writev` iovec on Native, `ByteBuffer[]` for `SocketChannel.write` on
 * JVM, etc.). Treat the values as read-only; mutation is reserved for the
 * [SegmentRangeList] that produced this cell.
 */
public class SegmentRange internal constructor() {

    /** The [SegmentBacking] for the window's segment. */
    public var memory: SegmentBacking? = null
        internal set

    /** Window start in the backing's local coordinate space. */
    public var offset: Int = 0
        internal set

    /** Window length in bytes. */
    public var length: Int = 0
        internal set

    internal fun set(memory: SegmentBacking, offset: Int, length: Int) {
        this.memory = memory
        this.offset = offset
        this.length = length
    }

    internal fun clear() {
        memory = null
        offset = 0
        length = 0
    }
}

/**
 * Allocation-amortised list of [SegmentRange] cells.
 *
 * A scratch instance is held by the iteration call site (engine / codec
 * write path) and refilled on each call. The initial backing array
 * pre-allocates [INITIAL_CAPACITY] cells; growth doubles the array but
 * never deallocates, so the steady-state cost is one already-allocated
 * cell per emitted range.
 *
 * Iteration pattern:
 * ```
 * val list = SegmentRangeList()
 * // ... later, hot path:
 * buf.fillReadableSegments(list)
 * for (i in 0 until list.size) {
 *     val r = list[i]
 *     // r.memory, r.offset, r.length
 * }
 * ```
 *
 * Not thread-safe — owned by a single EventLoop thread, same as the
 * [IoBuf] it iterates.
 */
public class SegmentRangeList {

    private var slots: Array<SegmentRange> = Array(INITIAL_CAPACITY) { SegmentRange() }
    private var _size: Int = 0

    /** Number of populated ranges. */
    public val size: Int get() = _size

    /** Returns the range at [index]. */
    public operator fun get(index: Int): SegmentRange {
        if (index < 0 || index >= _size) {
            throw IndexOutOfBoundsException("index=$index, size=$_size")
        }
        return slots[index]
    }

    /** Resets [size] to 0 without releasing any backing cells. */
    internal fun reset() {
        // Clear references in the populated slots so SegmentBacking
        // instances are not retained past their logical lifetime.
        for (i in 0 until _size) slots[i].clear()
        _size = 0
    }

    /**
     * Returns the next free cell (growing the backing array if needed)
     * and bumps [size]. The caller must populate the returned cell via
     * [SegmentRange.set] before the call returns.
     */
    internal fun acquireSlot(): SegmentRange {
        if (_size == slots.size) {
            val newCapacity = slots.size * 2
            val newSlots = arrayOfNulls<SegmentRange>(newCapacity)
            for (i in 0 until slots.size) newSlots[i] = slots[i]
            for (i in slots.size until newCapacity) newSlots[i] = SegmentRange()
            @Suppress("UNCHECKED_CAST")
            slots = newSlots as Array<SegmentRange>
        }
        return slots[_size++]
    }

    public companion object {
        private const val INITIAL_CAPACITY: Int = 8
    }
}
