package io.github.fukusaka.keel.buf

/**
 * Size-class table: maps an arbitrary requested size to the smallest pooled
 * size class that can satisfy it (round-up), and back.
 *
 * This is a faithful Kotlin port of Netty 4.2.12.Final's
 * `io.netty.buffer.SizeClasses` (Apache-2.0). It is **pure and stateless** — the
 * table is built once from `(pageSize, pageShifts, chunkSize, alignment)` at
 * construction and never mutates afterwards, so a single instance is safe to
 * share read-only across threads.
 *
 * ## Size-class scheme (jemalloc / Netty)
 *
 * Sizes are laid out in geometric groups. Within each doubling (octave) there
 * are `1 << LOG2_SIZE_CLASS_GROUP` (= 4) evenly spaced classes; the quantum
 * (smallest delta) is `1 << LOG2_QUANTUM` (= 16 bytes). The first few classes
 * are therefore `16, 32, 48, 64, 80, 96, 112, 128, 160, 192, 224, 256, ...`,
 * giving a bounded internal-fragmentation worst case of ~20–25 % (one quantum
 * over the request at the very smallest sizes, ~1/4 of a doubling at larger
 * sizes). This is the established answer Netty/jemalloc converged on; keel
 * adopts it rather than an ad-hoc ladder.
 *
 * The table splits into two regions:
 * - **subpage region** (`isSubpage`): classes below `pageSize << LOG2_SIZE_CLASS_GROUP`,
 *   which a chunk back-end would carve out of a single page via a bitmap.
 * - **run region**: page-multiple classes from `pageSize` up to `chunkSize`,
 *   which a chunk back-end would carve as runs of pages.
 *
 * ## What keel uses
 *
 * - [size2SizeIdx] / [sizeIdx2size] / [nSizes] drive the cache layer's
 *   class-index re-keying in [PooledAllocator] (the round-up that lets *any*
 *   requested size land in a pool, removing the old fixed-8K limitation).
 * - The page-index methods ([pages2pageIdx], [pageIdx2size], [nPSizes]) are the
 *   run-region accessors a future `PoolChunk` back-end consumes; they are ported
 *   here for fidelity with the source algorithm and exercised by unit tests, but
 *   the cache layer alone (no chunk back-end yet) only needs the size-index API.
 *
 * @param pageSize the page size in bytes; must be a power of two (keel default 8 KiB).
 * @param pageShifts `log2(pageSize)` (keel default 13).
 * @param chunkSize the largest pooled size; requests above it are "huge" and
 *   bypass pooling. Must be a multiple of `pageSize` and equal to the computed
 *   `normalMaxSize` (keel default 256 KiB).
 * @param directMemoryCacheAlignment optional alignment in bytes (0 = none); when
 *   positive, every class size is rounded up to a multiple of it.
 */
internal class SizeClasses(
    val pageSize: Int,
    val pageShifts: Int,
    val chunkSize: Int,
    val directMemoryCacheAlignment: Int,
) {
    /** Total number of size classes. A request larger than [chunkSize] maps to this sentinel index. */
    val nSizes: Int

    /** Number of subpage (small) classes. */
    val nSubpages: Int

    /** Number of page-multiple (run-region) classes. */
    val nPSizes: Int

    /** Largest size resolvable through the [size2idxTab] fast lookup. */
    val lookupMaxSize: Int

    /** Index of the largest subpage class. */
    val smallMaxSizeIdx: Int

    private val sizeIdx2sizeTab: IntArray
    private val pageIdx2sizeTab: IntArray
    private val size2idxTab: IntArray

    init {
        val group = log2(chunkSize) - LOG2_QUANTUM - LOG2_SIZE_CLASS_GROUP + 1

        // Each row: [index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubpage, log2DeltaLookup].
        val sizeClasses = Array(group shl LOG2_SIZE_CLASS_GROUP) { IntArray(NUM_COLUMNS) }

        var normalMaxSize = -1
        var localNSizes = 0
        var size = 0

        var log2Group = LOG2_QUANTUM
        var log2Delta = LOG2_QUANTUM
        val ndeltaLimit = 1 shl LOG2_SIZE_CLASS_GROUP

        // First group: nDelta in [0, ndeltaLimit).
        var nDelta = 0
        while (nDelta < ndeltaLimit) {
            val sizeClass = newSizeClass(localNSizes, log2Group, log2Delta, nDelta, pageShifts)
            sizeClasses[localNSizes] = sizeClass
            size = sizeOf(sizeClass, directMemoryCacheAlignment)
            nDelta++
            localNSizes++
        }

        log2Group += LOG2_SIZE_CLASS_GROUP

        // Remaining groups: nDelta in [1, ndeltaLimit], stop once size reaches chunkSize.
        while (size < chunkSize) {
            nDelta = 1
            while (nDelta <= ndeltaLimit && size < chunkSize) {
                val sizeClass = newSizeClass(localNSizes, log2Group, log2Delta, nDelta, pageShifts)
                sizeClasses[localNSizes] = sizeClass
                size = sizeOf(sizeClass, directMemoryCacheAlignment)
                normalMaxSize = size
                nDelta++
                localNSizes++
            }
            log2Group++
            log2Delta++
        }

        // chunkSize must coincide with the largest generated class.
        require(chunkSize == normalMaxSize) {
            "chunkSize=$chunkSize must equal the computed normalMaxSize=$normalMaxSize"
        }

        var localSmallMaxSizeIdx = 0
        var localLookupMaxSize = 0
        var localNPSizes = 0
        var localNSubpages = 0
        for (idx in 0 until localNSizes) {
            val sz = sizeClasses[idx]
            if (sz[PAGESIZE_IDX] == YES) {
                localNPSizes++
            }
            if (sz[SUBPAGE_IDX] == YES) {
                localNSubpages++
                localSmallMaxSizeIdx = idx
            }
            if (sz[LOG2_DELTA_LOOKUP_IDX] != NO) {
                localLookupMaxSize = sizeOf(sz, directMemoryCacheAlignment)
            }
        }
        smallMaxSizeIdx = localSmallMaxSizeIdx
        lookupMaxSize = localLookupMaxSize
        nPSizes = localNPSizes
        nSubpages = localNSubpages
        nSizes = localNSizes

        sizeIdx2sizeTab = newIdx2SizeTab(sizeClasses, localNSizes, directMemoryCacheAlignment)
        pageIdx2sizeTab = newPageIdx2sizeTab(sizeClasses, localNSizes, localNPSizes, directMemoryCacheAlignment)
        size2idxTab = newSize2idxTab(localLookupMaxSize, sizeClasses)
    }

    /**
     * Rounds [size] up to its size-class index. Returns [nSizes] (a sentinel,
     * one past the last valid index) when [size] exceeds [chunkSize] (huge).
     */
    fun size2SizeIdx(size: Int): Int {
        if (size == 0) {
            return 0
        }
        if (size > chunkSize) {
            return nSizes
        }

        val aligned = alignSizeIfNeeded(size, directMemoryCacheAlignment)

        if (aligned <= lookupMaxSize) {
            return size2idxTab[(aligned - 1) shr LOG2_QUANTUM]
        }

        val x = log2((aligned shl 1) - 1)
        val shift = if (x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1) {
            0
        } else {
            x - (LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM)
        }

        val group = shift shl LOG2_SIZE_CLASS_GROUP

        val log2Delta = if (x < LOG2_SIZE_CLASS_GROUP + LOG2_QUANTUM + 1) {
            LOG2_QUANTUM
        } else {
            x - LOG2_SIZE_CLASS_GROUP - 1
        }

        val mod = ((aligned - 1) shr log2Delta) and ((1 shl LOG2_SIZE_CLASS_GROUP) - 1)

        return group + mod
    }

    /** Returns the byte size of the class at [sizeIdx]. */
    fun sizeIdx2size(sizeIdx: Int): Int = sizeIdx2sizeTab[sizeIdx]

    /** Returns the byte size of the page-multiple class at [pageIdx]. */
    fun pageIdx2size(pageIdx: Int): Int = pageIdx2sizeTab[pageIdx]

    /** Rounds [pages] (page count) up to its page-index (run-region accessor). */
    fun pages2pageIdx(pages: Int): Int = pages2pageIdxCompute(pages, floor = false)

    /** Rounds [pages] (page count) down to its page-index (run-region accessor). */
    fun pages2pageIdxFloor(pages: Int): Int = pages2pageIdxCompute(pages, floor = true)

    private fun pages2pageIdxCompute(pages: Int, floor: Boolean): Int {
        val pageSizeBytes = pages shl pageShifts
        if (pageSizeBytes > chunkSize) {
            return nPSizes
        }

        val x = log2((pageSizeBytes shl 1) - 1)

        val shift = if (x < LOG2_SIZE_CLASS_GROUP + pageShifts) {
            0
        } else {
            x - (LOG2_SIZE_CLASS_GROUP + pageShifts)
        }

        val group = shift shl LOG2_SIZE_CLASS_GROUP

        val log2Delta = if (x < LOG2_SIZE_CLASS_GROUP + pageShifts + 1) {
            pageShifts
        } else {
            x - LOG2_SIZE_CLASS_GROUP - 1
        }

        val mod = ((pageSizeBytes - 1) shr log2Delta) and ((1 shl LOG2_SIZE_CLASS_GROUP) - 1)

        var pageIdx = group + mod

        if (floor && pageIdx2sizeTab[pageIdx] > (pages shl pageShifts)) {
            pageIdx--
        }

        return pageIdx
    }

    private fun newSizeClass(index: Int, log2Group: Int, log2Delta: Int, nDelta: Int, pageShifts: Int): IntArray {
        val isMultiPageSize: Int
        if (log2Delta >= pageShifts) {
            isMultiPageSize = YES
        } else {
            val pageSizeBytes = 1 shl pageShifts
            val size = calculateSize(log2Group, nDelta, log2Delta)
            isMultiPageSize = if (size == size / pageSizeBytes * pageSizeBytes) YES else NO
        }

        val log2Ndelta = if (nDelta == 0) 0 else log2(nDelta)

        var remove = if ((1 shl log2Ndelta) < nDelta) YES else NO

        val log2Size = if (log2Delta + log2Ndelta == log2Group) log2Group + 1 else log2Group
        if (log2Size == log2Group) {
            remove = YES
        }

        val isSubpage = if (log2Size < pageShifts + LOG2_SIZE_CLASS_GROUP) YES else NO

        val log2DeltaLookup = if (log2Size < LOG2_MAX_LOOKUP_SIZE ||
            (log2Size == LOG2_MAX_LOOKUP_SIZE && remove == NO)
        ) {
            log2Delta
        } else {
            NO
        }

        return intArrayOf(index, log2Group, log2Delta, nDelta, isMultiPageSize, isSubpage, log2DeltaLookup)
    }

    private companion object {
        const val LOG2_QUANTUM = 4
        const val LOG2_SIZE_CLASS_GROUP = 2
        const val LOG2_MAX_LOOKUP_SIZE = 12

        const val NUM_COLUMNS = 7
        const val LOG2GROUP_IDX = 1
        const val LOG2DELTA_IDX = 2
        const val NDELTA_IDX = 3
        const val PAGESIZE_IDX = 4
        const val SUBPAGE_IDX = 5
        const val LOG2_DELTA_LOOKUP_IDX = 6

        const val NO = 0
        const val YES = 1

        fun newIdx2SizeTab(sizeClasses: Array<IntArray>, nSizes: Int, directMemoryCacheAlignment: Int): IntArray {
            val table = IntArray(nSizes)
            for (i in 0 until nSizes) {
                table[i] = sizeOf(sizeClasses[i], directMemoryCacheAlignment)
            }
            return table
        }

        fun newPageIdx2sizeTab(
            sizeClasses: Array<IntArray>,
            nSizes: Int,
            nPSizes: Int,
            directMemoryCacheAlignment: Int,
        ): IntArray {
            val table = IntArray(nPSizes)
            var pageIdx = 0
            for (i in 0 until nSizes) {
                val sizeClass = sizeClasses[i]
                if (sizeClass[PAGESIZE_IDX] == YES) {
                    table[pageIdx++] = sizeOf(sizeClass, directMemoryCacheAlignment)
                }
            }
            return table
        }

        fun newSize2idxTab(lookupMaxSize: Int, sizeClasses: Array<IntArray>): IntArray {
            val table = IntArray(lookupMaxSize shr LOG2_QUANTUM)
            var idx = 0
            var size = 0
            var i = 0
            while (size <= lookupMaxSize) {
                val log2Delta = sizeClasses[i][LOG2DELTA_IDX]
                var times = 1 shl (log2Delta - LOG2_QUANTUM)

                while (size <= lookupMaxSize && times-- > 0) {
                    table[idx++] = i
                    size = (idx + 1) shl LOG2_QUANTUM
                }
                i++
            }
            return table
        }

        fun calculateSize(log2Group: Int, nDelta: Int, log2Delta: Int): Int =
            (1 shl log2Group) + (nDelta shl log2Delta)

        fun sizeOf(sizeClass: IntArray, directMemoryCacheAlignment: Int): Int {
            val log2Group = sizeClass[LOG2GROUP_IDX]
            val log2Delta = sizeClass[LOG2DELTA_IDX]
            val nDelta = sizeClass[NDELTA_IDX]
            val size = calculateSize(log2Group, nDelta, log2Delta)
            return alignSizeIfNeeded(size, directMemoryCacheAlignment)
        }

        fun alignSizeIfNeeded(size: Int, directMemoryCacheAlignment: Int): Int {
            if (directMemoryCacheAlignment <= 0) {
                return size
            }
            val delta = size and (directMemoryCacheAlignment - 1)
            return if (delta == 0) size else size + directMemoryCacheAlignment - delta
        }

        /** `floor(log2(value))` for `value > 0`. */
        fun log2(value: Int): Int = (Int.SIZE_BITS - 1) - value.countLeadingZeroBits()
    }
}
