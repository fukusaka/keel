package io.github.fukusaka.keel.buf

/**
 * Internal multi-segment chain primitive — the substrate that the
 * multi-segment [IoBuf] implementations delegate to.
 *
 * The chain holds a [primarySegment] (supplied at construction, valid
 * for the chain's lifetime) plus a lazy-allocated [extras] list of
 * follow-on segments. Each [Segment] keeps its own reference count (PoC
 * PR #602 decided on per-Segment refcount, "semantic B"). Segments are
 * appended explicitly via [appendSegment]; there is no implicit
 * auto-grow on write.
 *
 * Logical indexing: callers (the [IoBuf] view) maintain `readerIndex` /
 * `writerIndex` in *logical* coordinates spanning `[0, totalCapacity)`.
 * The chain maps logical indices onto `(segmentIndex, localOffset)` via
 * [locateLogical] and emits readable windows via
 * [forEachReadableSegment] / [fillReadableSegments].
 *
 * **Cap semantic**: [maxCapacity] bounds the *sum* of segment
 * capacities. [appendSegment] throws [KeelBufferOverflowException] when
 * adding the new segment would exceed it. Auto-grow logic in the
 * write path is the caller's responsibility — [SegmentChain] never
 * allocates segments itself.
 *
 * **Single-seg representation**: a freshly constructed chain holds only
 * the primary segment with [extras] still `null`. No `ArrayList` is
 * allocated until [appendSegment] is first called, so the hot path
 * (every short-response IoBuf the engine fills from a single recv) pays
 * for nothing beyond the chain object itself.
 *
 * **Refcount lifecycle**: [retainAll] / [releaseAll] iterate the chain
 * and apply the operation to every segment. Each [Segment]'s own
 * `release` decides what happens when its refcount reaches zero (the
 * heap-owned default frees the backing; pool-owned segments return
 * themselves to the originating pool). [SegmentChain] has no separate
 * lifecycle of its own — it is a thin organiser over its segments.
 *
 * **Thread safety**: single EventLoop ownership, identical to [IoBuf].
 * All mutating operations (append, retain, release) and reads must run
 * on the owner thread.
 */
internal class SegmentChain(
    private val primarySegment: Segment,
    val maxCapacity: Int,
) {

    /**
     * Lazily-allocated list of follow-on segments — `null` until the
     * first [appendSegment]. Keeps the single-segment IoBuf case at one
     * allocated object total (the chain itself).
     */
    private var extras: ArrayList<Segment>? = null

    private var _totalCapacity: Int = primarySegment.capacity

    init {
        require(maxCapacity >= primarySegment.capacity) {
            "maxCapacity ($maxCapacity) must be >= primary segment capacity (${primarySegment.capacity})"
        }
    }

    /** Sum of segment capacities currently in the chain. */
    val totalCapacity: Int get() = _totalCapacity

    /** Capacity of the primary segment — the fast-path window for hot byte ops. */
    val primaryCapacity: Int get() = primarySegment.capacity

    /** Number of segments in the chain (always `>= 1`). */
    val segmentCount: Int get() = 1 + (extras?.size ?: 0)

    /** Returns the primary segment (constant across the chain's lifetime). */
    fun primary(): Segment = primarySegment

    /** Returns the segment at [index] (`0 <= index < segmentCount`). */
    fun segmentAt(index: Int): Segment {
        if (index == 0) return primarySegment
        val list = extras
        if (list == null || index < 1 || index > list.size) {
            throw IndexOutOfBoundsException("index=$index, segmentCount=$segmentCount")
        }
        return list[index - 1]
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
        val list = extras ?: ArrayList<Segment>(EXTRAS_INITIAL_CAPACITY).also { extras = it }
        list.add(seg)
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
        // Primary segment window.
        val primaryEnd = primarySegment.capacity
        val pFrom = if (readerIdx > 0) readerIdx else 0
        val pTo = if (writerIdx < primaryEnd) writerIdx else primaryEnd
        if (pFrom < pTo) action.apply(primarySegment.backing, pFrom, pTo - pFrom)
        if (writerIdx <= primaryEnd) return
        // Extras.
        val list = extras ?: return
        var logicalStart = primaryEnd
        val n = list.size
        for (i in 0 until n) {
            val seg = list[i]
            val segEnd = logicalStart + seg.capacity
            val from = if (readerIdx > logicalStart) readerIdx else logicalStart
            val to = if (writerIdx < segEnd) writerIdx else segEnd
            if (from < to) action.apply(seg.backing, from - logicalStart, to - from)
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
        into.clear()
        appendReadableSegments(readerIdx, writerIdx, into)
    }

    /**
     * Appends the readable windows of `[readerIdx, writerIdx)` to [into]
     * without resetting it.
     *
     * Engines use this variant to accumulate iovec ranges across multiple
     * `IoBuf`s into a single shared list — one call per pending write, the
     * resulting list feeds `writev` / `SocketChannel.write(ByteBuffer[])`
     * with all gather entries at once. Preconditions identical to
     * [forEachReadableSegment].
     */
    fun appendReadableSegments(readerIdx: Int, writerIdx: Int, into: SegmentRangeList) {
        if (readerIdx >= writerIdx) return
        val primaryEnd = primarySegment.capacity
        val pFrom = if (readerIdx > 0) readerIdx else 0
        val pTo = if (writerIdx < primaryEnd) writerIdx else primaryEnd
        if (pFrom < pTo) into.acquireSlot().set(primarySegment.backing, pFrom, pTo - pFrom)
        if (writerIdx <= primaryEnd) return
        val list = extras ?: return
        var logicalStart = primaryEnd
        val n = list.size
        for (i in 0 until n) {
            val seg = list[i]
            val segEnd = logicalStart + seg.capacity
            val from = if (readerIdx > logicalStart) readerIdx else logicalStart
            val to = if (writerIdx < segEnd) writerIdx else segEnd
            if (from < to) into.acquireSlot().set(seg.backing, from - logicalStart, to - from)
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
        val primaryEnd = primarySegment.capacity
        if (logicalIdx < primaryEnd) {
            return packLocateResult(0, logicalIdx)
        }
        val list = extras
            ?: error("locateLogical: extras null but logicalIdx=$logicalIdx >= primaryEnd=$primaryEnd")
        var logicalStart = primaryEnd
        val n = list.size
        for (i in 0 until n) {
            val seg = list[i]
            val segEnd = logicalStart + seg.capacity
            if (logicalIdx < segEnd) {
                return packLocateResult(i + 1, logicalIdx - logicalStart)
            }
            logicalStart = segEnd
        }
        // Unreachable: bounds-checked above.
        error("locateLogical: walk exhausted with logicalIdx=$logicalIdx, totalCapacity=$_totalCapacity")
    }

    /** Increments the refcount of every segment in the chain. */
    fun retainAll() {
        primarySegment.retain()
        val list = extras ?: return
        val n = list.size
        for (i in 0 until n) list[i].retain()
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
        var any = primarySegment.release()
        val list = extras ?: return any
        val n = list.size
        for (i in 0 until n) {
            if (list[i].release()) any = true
        }
        return any
    }

    companion object {
        private const val EXTRAS_INITIAL_CAPACITY: Int = 4
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
