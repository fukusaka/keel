package io.github.fukusaka.keel.buf

/**
 * Internal multi-segment chain primitive — the substrate that the
 * multi-segment [IoBuf] implementations delegate to.
 *
 * The chain holds an ordered list of [Segment]s. Each [Segment] keeps
 * its own reference count (PoC PR #602 decided on per-Segment refcount,
 * "semantic B") and is appended explicitly via [appendSegment]; there
 * is no implicit auto-grow on write. The first segment is the "primary"
 * and is supplied at construction.
 *
 * Logical indexing: callers (the [IoBuf] view in PR-2) maintain
 * `readerIndex` / `writerIndex` in *logical* coordinates spanning
 * `[0, totalCapacity)`. The chain maps logical indices onto
 * `(segmentIndex, localOffset)` via [locateLogical] and emits
 * readable windows via [forEachReadableSegment] / [fillReadableSegments].
 *
 * **Cap semantic**: [maxCapacity] bounds the *sum* of segment
 * capacities. [appendSegment] throws [KeelBufferOverflowException] when
 * adding the new segment would exceed it. Auto-grow logic in the
 * write path is the caller's responsibility — [SegmentChain] never
 * allocates segments itself.
 *
 * **Refcount lifecycle**: [retainAll] / [releaseAll] iterate the chain
 * and apply the operation to every segment. Releasing the chain's
 * primary segment also releases the segment's cached [Segment.view]
 * memory if the primary's refcount reaches zero.
 *
 * **Thread safety**: single EventLoop ownership, identical to [IoBuf].
 * All mutating operations (append, retain, release) and reads must run
 * on the owner thread.
 */
internal class SegmentChain(
    primary: Segment,
    val maxCapacity: Int,
) {

    /**
     * Ordered list of segments. `segments[0]` is the primary (the head)
     * and stays valid for the chain's lifetime; subsequent entries are
     * appended via [appendSegment].
     */
    private val segments: ArrayList<Segment> = ArrayList<Segment>(INITIAL_SEGMENT_LIST_CAPACITY).also {
        it.add(primary)
    }

    private var _totalCapacity: Int = primary.capacity

    init {
        require(maxCapacity >= primary.capacity) {
            "maxCapacity ($maxCapacity) must be >= primary segment capacity (${primary.capacity})"
        }
    }

    /** Sum of segment capacities currently in the chain. */
    val totalCapacity: Int get() = _totalCapacity

    /** Number of segments in the chain. */
    val segmentCount: Int get() = segments.size

    /** Returns the primary segment (constant across the chain's lifetime). */
    fun primary(): Segment = segments[0]

    /** Returns the segment at [index] (`0 <= index < segmentCount`). */
    fun segmentAt(index: Int): Segment {
        if (index < 0 || index >= segments.size) {
            throw IndexOutOfBoundsException("index=$index, segmentCount=${segments.size}")
        }
        return segments[index]
    }

    /**
     * Appends [seg] to the tail. The caller transfers ownership of the
     * segment's reference to the chain.
     *
     * @throws KeelBufferOverflowException if the new total would exceed
     *   [maxCapacity].
     */
    fun appendSegment(seg: Segment) {
        val newTotal = _totalCapacity + seg.capacity
        if (newTotal > maxCapacity) {
            throw KeelBufferOverflowException(
                "appendSegment would exceed maxCapacity: " +
                    "current=$_totalCapacity, appending=${seg.capacity}, maxCapacity=$maxCapacity",
            )
        }
        segments.add(seg)
        _totalCapacity = newTotal
    }

    /**
     * Calls [action] once for each readable window in
     * `[readerIdx, writerIdx)`, in chain order from head to tail.
     *
     * Preconditions: `0 <= readerIdx <= writerIdx <= totalCapacity`.
     * The bounds are not asserted in production — the caller (the
     * [IoBuf] view) maintains the invariant.
     */
    fun forEachReadableSegment(readerIdx: Int, writerIdx: Int, action: SegmentRangeAction) {
        if (readerIdx >= writerIdx) return
        var logicalStart = 0
        val n = segments.size
        for (i in 0 until n) {
            val seg = segments[i]
            val segEnd = logicalStart + seg.capacity
            val from = if (readerIdx > logicalStart) readerIdx else logicalStart
            val to = if (writerIdx < segEnd) writerIdx else segEnd
            if (from < to) {
                action.apply(seg.backing, from - logicalStart, to - from)
            }
            if (writerIdx <= segEnd) return
            logicalStart = segEnd
        }
    }

    /**
     * Fills [into] with the readable windows in `[readerIdx, writerIdx)`,
     * in chain order from head to tail. Resets [into] before populating.
     *
     * Preconditions identical to [forEachReadableSegment].
     */
    fun fillReadableSegments(readerIdx: Int, writerIdx: Int, into: SegmentRangeList) {
        into.reset()
        if (readerIdx >= writerIdx) return
        var logicalStart = 0
        val n = segments.size
        for (i in 0 until n) {
            val seg = segments[i]
            val segEnd = logicalStart + seg.capacity
            val from = if (readerIdx > logicalStart) readerIdx else logicalStart
            val to = if (writerIdx < segEnd) writerIdx else segEnd
            if (from < to) {
                into.acquireSlot().set(seg.backing, from - logicalStart, to - from)
            }
            if (writerIdx <= segEnd) return
            logicalStart = segEnd
        }
    }

    /**
     * Maps logical index [logicalIdx] (in `[0, totalCapacity)`) to a
     * `(segmentIndex, localOffset)` pair packed into a [Long]:
     * the segment index occupies the high 32 bits and the local offset
     * the low 32 bits.
     *
     * Allocation-free; intended for the IoBuf cross-segment slow path
     * ([IoBuf.getByte] / cross-segment write boundary). The fast path
     * uses the cached primary [Segment]'s backing directly.
     *
     * @throws IndexOutOfBoundsException if [logicalIdx] falls outside
     *   `[0, totalCapacity)`.
     */
    fun locateLogical(logicalIdx: Int): Long {
        if (logicalIdx < 0 || logicalIdx >= _totalCapacity) {
            throw IndexOutOfBoundsException(
                "logicalIdx=$logicalIdx, totalCapacity=$_totalCapacity",
            )
        }
        var logicalStart = 0
        val n = segments.size
        for (i in 0 until n) {
            val seg = segments[i]
            val segEnd = logicalStart + seg.capacity
            if (logicalIdx < segEnd) {
                return packLocateResult(i, logicalIdx - logicalStart)
            }
            logicalStart = segEnd
        }
        // Unreachable: bounds-checked above.
        error("locateLogical: walk exhausted with logicalIdx=$logicalIdx, totalCapacity=$_totalCapacity")
    }

    /** Increments the refcount of every segment in the chain. */
    fun retainAll() {
        val n = segments.size
        for (i in 0 until n) segments[i].retain()
    }

    /**
     * Decrements the refcount of every segment in the chain.
     *
     * Returns `true` iff at least one segment was reclaimed (its
     * refcount reached zero). The chain itself is not zeroed; callers
     * that intend to reuse the [SegmentChain] after a full release must
     * reconstruct it.
     */
    fun releaseAll(): Boolean {
        var any = false
        val n = segments.size
        for (i in 0 until n) {
            if (segments[i].release()) any = true
        }
        return any
    }

    companion object {
        private const val INITIAL_SEGMENT_LIST_CAPACITY: Int = 4
    }
}

/** Packs `(segmentIndex, localOffset)` for the [SegmentChain.locateLogical] return shape. */
private fun packLocateResult(segmentIndex: Int, localOffset: Int): Long =
    (segmentIndex.toLong() shl LOCATE_SEG_SHIFT) or localOffset.toLong()

/** Extracts the segment index from a packed result of [SegmentChain.locateLogical]. */
internal fun unpackLocateSegmentIndex(packed: Long): Int =
    (packed ushr LOCATE_SEG_SHIFT).toInt()

/** Extracts the local offset from a packed result of [SegmentChain.locateLogical]. */
internal fun unpackLocateLocalOffset(packed: Long): Int =
    (packed and LOCATE_OFFSET_MASK).toInt()

private const val LOCATE_SEG_SHIFT: Int = 32
private const val LOCATE_OFFSET_MASK: Long = 0xFFFF_FFFFL
