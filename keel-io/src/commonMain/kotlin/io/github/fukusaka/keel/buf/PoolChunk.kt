package io.github.fukusaka.keel.buf

/**
 * Run/subpage bookkeeping for a single pooled memory chunk, modelled on Netty
 * 4.2.12.Final's `io.netty.buffer.PoolChunk` (the jemalloc-derived run allocator).
 *
 * A chunk of [chunkSize] bytes is divided into `chunkSize / pageSize` pages. The
 * allocator hands out **runs** (contiguous page spans) for normal sizes and
 * subdivides a run into a [PoolSubpage] bitmap for small sizes. This class tracks
 * *which* pages are free using pure integer math — it owns **no backing memory**.
 * The backing buffer and the conversion `handle -> (byteOffset, length)` belong to
 * the wiring layer (Phase 4); a handle here only encodes page coordinates.
 *
 * ## Handle layout (64 bits)
 *
 * ```
 *  bit 63 .......... 49 48 ......... 34 33      32       31 ............ 0
 *     runOffset (15)        runPages (15)  isUsed  isSubpage    bitmapIdx (32)
 * ```
 *
 * A free run carries `isUsed = 0`, `isSubpage = 0`, `bitmapIdx = 0`, so its raw
 * `Long` orders by `(runOffset, runPages)` — exactly what the per-size-class
 * [LongPriorityQueue] in [runsAvail] needs (lowest-offset run allocated first).
 *
 * ## Free-run index
 *
 * - [runsAvail] — one [LongPriorityQueue] per page-size class (indexed by
 *   [SizeClasses.pages2pageIdxFloor] on insert, scanned from
 *   [SizeClasses.pages2pageIdx] up on allocate). Holds every free run.
 * - [runsAvailMap] — a sparse [LongLongHashMap] from `pageOffset -> handle` for
 *   the **first and last** page of every free run, giving O(1) neighbour lookup
 *   when coalescing. A flat array would be dense over a key space that is *not*
 *   fixed (page and chunk sizes are configurable, so a large chunk with a small
 *   page can have thousands of pages while only a handful of runs are free); the
 *   map's footprint scales with live entries instead. `0` means "no run".
 *
 * **Thread safety.** None of its own — the wiring layer serialises access (Netty's
 * arena holds `runsAvailLock`). Phase 3 is single-threaded pure logic.
 *
 * @param sizeClasses the size-class table shared with the allocator.
 */
internal class PoolChunk(val sizeClasses: SizeClasses) {
    val pageSize: Int = sizeClasses.pageSize
    val pageShifts: Int = sizeClasses.pageShifts
    val chunkSize: Int = sizeClasses.chunkSize
    private val chunkPages: Int = chunkSize ushr pageShifts

    /** Free run bytes (subpage-internal free elements do not count). */
    var freeBytes: Int = chunkSize
        private set

    private val runsAvail: Array<LongPriorityQueue> = Array(sizeClasses.nPSizes) { LongPriorityQueue() }
    private val runsAvailMap = LongLongHashMap() // pageOffset -> handle (sparse), 0 = absent
    private val subpages = arrayOfNulls<PoolSubpage>(chunkPages)

    init {
        // The whole chunk starts as one free run at offset 0.
        val initHandle = toRunHandle(0, chunkPages, inUsed = 0)
        insertAvailRun(0, chunkPages, initHandle)
    }

    /**
     * Allocates a run of at least [runSize] bytes (a multiple of [pageSize]).
     * Returns the run handle, or [NO_HANDLE] when the chunk cannot satisfy it.
     */
    fun allocateRun(runSize: Int): Long {
        val pages = runSize ushr pageShifts
        val pageIdx = sizeClasses.pages2pageIdx(pages)
        val queueIdx = runFirstBestFit(pageIdx)
        if (queueIdx == -1) return NO_HANDLE
        val queue = runsAvail[queueIdx]
        val handle = queue.poll()
        // [runFirstBestFit] only returns a non-`-1` index for a non-empty queue,
        // so [poll] must hand back a *well-formed free-run handle* here — never
        // [LongPriorityQueue.NO_VALUE] (the empty marker), and never a handle
        // whose structural fields contradict "free run" (`isUsed`, `isSubpage`,
        // any non-zero `bitmapIdx`, zero `runPages`, out-of-range `runOffset`).
        // Each of those would indicate corruption upstream of this call — a
        // queue heap-invariant break, a `runsAvail` / `subpages` cross-contamination,
        // or a [splitLargeRun] under-flow that produced a degenerate run handle —
        // and the right place to make the violation loud is the trust boundary
        // between the queue and the chunk's allocator state. Validating here
        // catches the previous task #75 surface (`handle = 0L`), plus future
        // regressions that would otherwise slip through `splitLargeRun` and
        // surface several layers downstream (in `PoolSubpage`, `ChunkArena`,
        // or `PooledAllocator.close()`) where the originating bug is hard to
        // reach from the failure site.
        require(isFreeRunHandle(handle, queueIdx))
        // poll() already removed it from the queue; drop its map endpoints too.
        removeAvailRunFromMap(handle)
        val allocated = splitLargeRun(handle, pages)
        freeBytes -= runSize(pageShifts, allocated)
        return allocated
    }

    /**
     * Validates that [handle] is a well-formed free-run handle. A free run
     * has `isUsed = 0`, `isSubpage = 0`, `bitmapIdx = 0`, `runPages >= 1`, and
     * `runOffset < chunkPages`; any deviation means the chunk's free-run index
     * has been corrupted. Throws [IllegalStateException] with a diagnostic
     * message that names which field failed and which `runsAvail[queueIdx]`
     * surfaced the violation, so the failure points at the originating layer
     * rather than the layer that happened to dereference it first.
     */
    private fun isFreeRunHandle(handle: Long, queueIdx: Int): Boolean {
        if (handle == LongPriorityQueue.NO_VALUE) {
            error("runsAvail[$queueIdx] reported non-empty but poll returned NO_VALUE — heap invariant violated")
        }
        if (handle <= 0L) {
            error("runsAvail[$queueIdx] returned non-positive handle $handle — handle value-space invariant violated")
        }
        if (isUsed(handle)) {
            error("runsAvail[$queueIdx] returned a used handle $handle — used-handle leak into free-run index")
        }
        if (isSubpage(handle)) {
            error("runsAvail[$queueIdx] returned a subpage handle $handle — subpage-handle leak into run queue")
        }
        if (bitmapIdx(handle) != 0) {
            error("runsAvail[$queueIdx] returned handle $handle with non-zero bitmapIdx — low-bits corruption")
        }
        if (runPages(handle) <= 0) {
            error("runsAvail[$queueIdx] returned handle $handle with runPages=0 — degenerate run handle")
        }
        if (runOffset(handle) >= chunkPages) {
            error("runsAvail[$queueIdx] returned handle $handle with runOffset >= $chunkPages — out-of-range offset")
        }
        return true
    }

    /**
     * Carves a fresh [PoolSubpage] for size class [sizeIdx] (a small class),
     * links it into [head]'s pool, and returns its first element's handle.
     * Returns [NO_HANDLE] when no run is available. Reusing an existing partially
     * free subpage across chunks is the arena's job (Phase 4) — this only creates
     * a new subpage in this chunk.
     */
    fun allocateSubpage(sizeIdx: Int, head: PoolSubpage, owningChunk: PooledChunk? = null): Long {
        val runSize = calculateRunSize(sizeIdx)
        val runHandle = allocateRun(runSize)
        if (runHandle == NO_HANDLE) return NO_HANDLE
        val runOffset = runOffset(runHandle)
        val elemSize = sizeClasses.sizeIdx2size(sizeIdx)
        val subpage = PoolSubpage.create(
            head = head,
            pageShifts = pageShifts,
            runOffset = runOffset,
            runSize = runSize(pageShifts, runHandle),
            elemSize = elemSize,
            sizeIdx = sizeIdx,
            owningChunk = owningChunk,
        )
        subpages[runOffset] = subpage
        return subpage.allocate()
    }

    /**
     * Frees [handle]. For a subpage element, [head] must be the element's
     * size-class pool head; the run is returned to the chunk only once the
     * subpage's last element is freed. Adjacent free runs are coalesced.
     */
    fun free(handle: Long, head: PoolSubpage?) {
        if (isSubpage(handle)) {
            val runOffset = runOffset(handle)
            val subpage = subpages[runOffset]
            checkNotNull(subpage) { "no subpage at run offset $runOffset" }
            checkNotNull(head) { "subpage free requires the size-class pool head" }
            if (subpage.free(head, bitmapIdx(handle))) {
                return // subpage still has live elements; its run stays allocated
            }
            subpages[runOffset] = null
            // Fall through to free the now-empty subpage's run.
        }
        freeBytes += runSize(pageShifts, handle)
        var finalRun = collapseRuns(handle)
        finalRun = finalRun and (1L shl IS_USED_SHIFT).inv()
        finalRun = finalRun and (1L shl IS_SUBPAGE_SHIFT).inv()
        insertAvailRun(runOffset(finalRun), runPages(finalRun), finalRun)
    }

    /**
     * Byte offset of [handle]'s allocation within the chunk's backing — the
     * wiring layer (Phase 4) adds this to the chunk's base pointer to carve a
     * view. For a run it is `runOffset << pageShifts`; for a subpage element it
     * adds `bitmapIdx * elemSize`.
     */
    fun byteOffset(handle: Long): Int {
        val runByteOffset = runOffset(handle) shl pageShifts
        if (!isSubpage(handle)) return runByteOffset
        val subpage = checkNotNull(subpages[runOffset(handle)]) { "no subpage at run offset ${runOffset(handle)}" }
        return runByteOffset + bitmapIdx(handle) * subpage.elemSize
    }

    /** Size-class index of the subpage [handle] points into (subpage handles only). */
    fun subpageSizeIdx(handle: Long): Int =
        checkNotNull(subpages[runOffset(handle)]) { "no subpage at run offset ${runOffset(handle)}" }.sizeIdx

    // --- run carving ---------------------------------------------------------

    private fun splitLargeRun(handle: Long, needPages: Int): Long {
        val totalPages = runPages(handle)
        val remPages = totalPages - needPages
        if (remPages > 0) {
            val runOffset = runOffset(handle)
            val remOffset = runOffset + needPages
            val remRun = toRunHandle(remOffset, remPages, inUsed = 0)
            insertAvailRun(remOffset, remPages, remRun)
            return toRunHandle(runOffset, needPages, inUsed = 1)
        }
        return handle or (1L shl IS_USED_SHIFT)
    }

    private fun runFirstBestFit(pageIdx: Int): Int {
        if (freeBytes == chunkSize) return sizeClasses.nPSizes - 1
        for (i in pageIdx until sizeClasses.nPSizes) {
            if (!runsAvail[i].isEmpty()) return i
        }
        return -1
    }

    private fun calculateRunSize(sizeIdx: Int): Int {
        val maxElements = 1 shl (pageShifts - SizeClasses.LOG2_QUANTUM)
        val elemSize = sizeClasses.sizeIdx2size(sizeIdx)
        var runSize = 0
        var nElements: Int
        // Grow by whole pages until we either reach the element cap or the run
        // divides evenly into elements (no wasted tail).
        do {
            runSize += pageSize
            nElements = runSize / elemSize
        } while (nElements < maxElements && runSize != nElements * elemSize)
        // If we overshot the element cap, trim back.
        while (nElements > maxElements) {
            runSize -= pageSize
            nElements = runSize / elemSize
        }
        return runSize
    }

    // --- coalescing ----------------------------------------------------------

    private fun collapseRuns(handle: Long): Long = collapseNext(collapsePast(handle))

    private fun collapsePast(handle: Long): Long {
        var current = handle
        while (true) {
            val runOffset = runOffset(current)
            val runPages = runPages(current)
            val pastRun = availRunAt(runOffset - 1)
            if (pastRun == NO_HANDLE) return current
            val pastOffset = runOffset(pastRun)
            val pastPages = runPages(pastRun)
            if (pastRun != current && pastOffset + pastPages == runOffset) {
                removeAvailRun(pastRun)
                current = toRunHandle(pastOffset, pastPages + runPages, inUsed = 0)
            } else {
                return current
            }
        }
    }

    private fun collapseNext(handle: Long): Long {
        var current = handle
        while (true) {
            val runOffset = runOffset(current)
            val runPages = runPages(current)
            val nextRun = availRunAt(runOffset + runPages)
            if (nextRun == NO_HANDLE) return current
            val nextOffset = runOffset(nextRun)
            val nextPages = runPages(nextRun)
            if (nextRun != current && runOffset + runPages == nextOffset) {
                removeAvailRun(nextRun)
                current = toRunHandle(runOffset, runPages + nextPages, inUsed = 0)
            } else {
                return current
            }
        }
    }

    /** Free run registered at [pageOffset] (first or last page), or [NO_HANDLE]. */
    private fun availRunAt(pageOffset: Int): Long {
        if (pageOffset < 0 || pageOffset >= chunkPages) return NO_HANDLE
        val handle = runsAvailMap.get(pageOffset.toLong())
        return if (handle == 0L) NO_HANDLE else handle
    }

    // --- free-run index maintenance ------------------------------------------

    private fun insertAvailRun(runOffset: Int, pages: Int, handle: Long) {
        val pageIdxFloor = sizeClasses.pages2pageIdxFloor(pages)
        runsAvail[pageIdxFloor].offer(handle)
        runsAvailMap.put(runOffset.toLong(), handle)
        if (pages > 1) {
            runsAvailMap.put((runOffset + pages - 1).toLong(), handle)
        }
    }

    private fun removeAvailRun(handle: Long) {
        val pageIdxFloor = sizeClasses.pages2pageIdxFloor(runPages(handle))
        runsAvail[pageIdxFloor].remove(handle)
        removeAvailRunFromMap(handle)
    }

    private fun removeAvailRunFromMap(handle: Long) {
        val runOffset = runOffset(handle)
        val pages = runPages(handle)
        runsAvailMap.remove(runOffset.toLong())
        if (pages > 1) {
            runsAvailMap.remove((runOffset + pages - 1).toLong())
        }
    }

    companion object {
        /** Sentinel for "no allocation"/"no run". */
        const val NO_HANDLE: Long = -1L

        const val IS_SUBPAGE_SHIFT = 32
        const val IS_USED_SHIFT = 33
        const val SIZE_SHIFT = 34
        const val RUN_OFFSET_SHIFT = 49
        private const val FIELD_MASK = 0x7fffL // 15-bit runOffset / runPages fields

        /** First page index of the run. */
        fun runOffset(handle: Long): Int = ((handle ushr RUN_OFFSET_SHIFT) and FIELD_MASK).toInt()

        /** Page span of the run. */
        fun runPages(handle: Long): Int = ((handle ushr SIZE_SHIFT) and FIELD_MASK).toInt()

        /** Run length in bytes. */
        fun runSize(pageShifts: Int, handle: Long): Int = runPages(handle) shl pageShifts

        fun isUsed(handle: Long): Boolean = (handle ushr IS_USED_SHIFT) and 1L == 1L

        fun isSubpage(handle: Long): Boolean = (handle ushr IS_SUBPAGE_SHIFT) and 1L == 1L

        fun isRun(handle: Long): Boolean = !isSubpage(handle)

        /** Element index within a subpage (low 32 bits). */
        fun bitmapIdx(handle: Long): Int = handle.toInt()

        /** Packs a run's coordinates into a handle (`bitmapIdx`/`isSubpage` are 0). */
        fun toRunHandle(runOffset: Int, runPages: Int, inUsed: Int): Long =
            (runOffset.toLong() shl RUN_OFFSET_SHIFT) or
                (runPages.toLong() shl SIZE_SHIFT) or
                (inUsed.toLong() shl IS_USED_SHIFT)
    }
}
