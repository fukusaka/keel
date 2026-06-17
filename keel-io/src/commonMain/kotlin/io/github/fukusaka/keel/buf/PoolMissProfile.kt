@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

import kotlin.concurrent.atomics.AtomicLong

/**
 * Per-size-class pool-hit / pool-miss histogram for [PooledAllocator],
 * implemented as a specialized [BufferAllocatorStatsCounter] that retains the
 * per-class index for in-process profiling.
 *
 * Complements [AllocationProfile] (size-distribution histogram captured at the
 * public [BufferAllocator.allocate] boundary, before pool round-up) by recording
 * what happens **inside** the pool dispatch: which class index was requested,
 * whether the per-size-class freelist served it (a *hit*), or whether the
 * request fell through to [ChunkArena.carve] (a *miss*). The two halves answer
 * different questions:
 *
 * - [AllocationProfile]: "what sizes does production traffic ask for?" — informs
 *   size-class ladder / chunk-size choices.
 * - [PoolMissProfile]: "what does the pool do with those requests?" — informs
 *   thread-safety lock-scope decisions (Option A single arena mutex vs Option B
 *   Netty-style per-size-class subpage head lock + arena chunk lock). The lock
 *   cost is paid on the **miss** path; a workload where misses concentrate in
 *   one size class gains nothing from per-class parallelism, while one where
 *   misses spread across many classes benefits from it.
 *
 * **Path taxonomy** (mirrors [AllocPath] and the branches in
 * [PooledAllocator.allocate]):
 * - **hit** ([AllocPath.HIT]): pool freelist served the request without touching
 *   the chunk-arena. The hot path; cheap, lock-free.
 * - **miss** ([AllocPath.MISS]): freelist was empty for this class →
 *   `chunkArena.carve` ran. The contended path; where the planned mutex /
 *   locks fire.
 * - **empty** ([AllocPath.EMPTY]): `allocate(0)` — the empty-buffer marker,
 *   neither pooled nor chunk-backed.
 * - **huge** ([AllocPath.HUGE]): request exceeds
 *   [PooledAllocator.MAX_CACHED_CAPACITY] — straight `newBuffer(capacity)`
 *   per system allocation, no pool involvement.
 *
 * **Adapter shape (item 4 resolution, 2026-06-17)**: implements
 * [BufferAllocatorStatsCounter] so callers can pass an instance to any
 * allocator that accepts the generic stats hook. The per-class detail is
 * retained because the [BufferAllocatorStatsCounter.onAllocate] signature
 * carries `classIdx` alongside the OT-shaped `sizeTier`; this profile
 * records into per-class arrays (not per-tier) so the existing
 * `--profile-alloc` output keeps full size-class granularity. OT adapters
 * that consume the same callback see only the `sizeTier` bucket and ignore
 * `classIdx`. The legacy [recordHit] / [recordMiss] / [recordEmpty] /
 * [recordHuge] entry points remain for backward compatibility with code that
 * does not yet route through the generic counter.
 *
 * **Release callback**: deliberately a no-op. This profile tracks the
 * allocate side only — the question it answers (pool hit vs miss vs huge) has
 * no symmetric notion on release. A separate counter that tracks release
 * outcomes can be installed alongside via the same hook.
 *
 * **Thread safety**: per-class counters are [Array]<[AtomicLong]> (one slot per
 * size class plus the four scalar counters). A single profile instance may be
 * shared across per-EventLoop [PooledAllocator] children — see
 * [PooledAllocator.createChild] propagation — so all EventLoops aggregate into
 * one histogram.
 *
 * @param nSizes Number of size classes; must match the [SizeClasses] used by
 *   the [PooledAllocator]. Pass [PooledAllocator]-internal `sizeClasses.nSizes`.
 */
class PoolMissProfile(private val nSizes: Int) : BufferAllocatorStatsCounter {
    private val hits: Array<AtomicLong> = Array(nSizes) { AtomicLong(0) }
    private val misses: Array<AtomicLong> = Array(nSizes) { AtomicLong(0) }
    private val emptyAllocations = AtomicLong(0)
    private val hugeAllocations = AtomicLong(0)

    /**
     * Records an allocation observation. Dispatches on [path] into the
     * per-classIdx or scalar counter; [byteSize] / [sizeTier] are accepted to
     * satisfy the interface but not stored (the legacy output is per-classIdx
     * and aggregate; tier bucketing is an OT adapter concern).
     *
     * The HUGE / EMPTY paths receive `classIdx = -1` from [PooledAllocator];
     * those values are recorded against the scalar counters and the index is
     * not used. HIT / MISS paths must supply a valid `classIdx`.
     */
    override fun onAllocate(
        byteSize: Int,
        classIdx: Int,
        sizeTier: SizeTier,
        path: AllocPath,
        weight: Int,
    ) {
        val w = weight.toLong()
        when (path) {
            AllocPath.HIT -> hits[classIdx].fetchAndAdd(w)
            AllocPath.MISS -> misses[classIdx].fetchAndAdd(w)
            AllocPath.EMPTY -> emptyAllocations.fetchAndAdd(w)
            AllocPath.HUGE -> hugeAllocations.fetchAndAdd(w)
        }
    }

    /**
     * Release-side no-op. This profile tracks the allocate side only — see
     * the class KDoc rationale. A separate counter can be installed alongside
     * if release-outcome aggregation is required.
     */
    override fun onRelease(
        classIdx: Int,
        sizeTier: SizeTier,
        outcome: ReleaseOutcome,
        weight: Int,
    ) {
        // Intentionally empty.
    }

    /** Records a pool freelist hit at size-class index [sizeIdx]. */
    fun recordHit(sizeIdx: Int) {
        hits[sizeIdx].fetchAndAdd(1)
    }

    /** Records a pool freelist miss (chunk-arena carve) at size-class index [sizeIdx]. */
    fun recordMiss(sizeIdx: Int) {
        misses[sizeIdx].fetchAndAdd(1)
    }

    /** Records an `allocate(0)` call (empty-marker fast path). */
    fun recordEmpty() {
        emptyAllocations.fetchAndAdd(1)
    }

    /**
     * Records an allocation that bypasses the pool entirely because its
     * requested capacity exceeds [PooledAllocator.MAX_CACHED_CAPACITY] (or
     * falls outside the size-class ladder). The chunk-arena is not touched.
     */
    fun recordHuge() {
        hugeAllocations.fetchAndAdd(1)
    }

    /** Returns a point-in-time copy of the per-size-class hit counts. */
    fun hitsSnapshot(): LongArray = LongArray(nSizes) { hits[it].load() }

    /** Returns a point-in-time copy of the per-size-class miss counts. */
    fun missesSnapshot(): LongArray = LongArray(nSizes) { misses[it].load() }

    /** Returns the empty-allocation count. */
    fun empties(): Long = emptyAllocations.load()

    /** Returns the huge-allocation (pool-bypass) count. */
    fun huges(): Long = hugeAllocations.load()

    /** Total recorded allocations across all paths. */
    fun total(): Long {
        var sum = empties() + huges()
        for (i in 0 until nSizes) sum += hits[i].load() + misses[i].load()
        return sum
    }

    /** Resets all counters to zero. */
    fun reset() {
        for (i in 0 until nSizes) {
            hits[i].store(0)
            misses[i].store(0)
        }
        emptyAllocations.store(0)
        hugeAllocations.store(0)
    }

    /**
     * Renders the profile as a human-readable multi-line table: one row per
     * size class that saw activity, plus a summary footer with totals + the
     * overall miss ratio (the headline number for Option A vs B evaluation).
     * Class size is reported via [sizeIdx2size] so the caller maps `sizeIdx`
     * back to bytes.
     */
    fun format(sizeIdx2size: (Int) -> Int): String {
        val hitsSnap = hitsSnapshot()
        val missesSnap = missesSnapshot()
        val empties = empties()
        val huges = huges()
        val total = total()
        if (total == 0L) return "PoolMissProfile: (no allocations recorded)"
        val sb = StringBuilder()
        sb.append("PoolMissProfile: total=").append(total).append(" allocations\n")
        sb.append("  idx").append(SIZE_PAD).append("size")
            .append(COUNT_PAD).append("hits")
            .append(COUNT_PAD).append("misses")
            .append("  miss%\n")
        var totalHits = 0L
        var totalMisses = 0L
        for (i in 0 until nSizes) {
            val h = hitsSnap[i]
            val m = missesSnap[i]
            if (h == 0L && m == 0L) continue
            totalHits += h
            totalMisses += m
            val classMissPct = if (h + m == 0L) 0L else m * PERCENT_SCALE / (h + m)
            sb.append("  ").append(i.toString().padStart(IDX_PAD_W))
                .append(sizeIdx2size(i).toString().padStart(SIZE_PAD_W))
                .append(h.toString().padStart(COUNT_PAD_W))
                .append(m.toString().padStart(COUNT_PAD_W))
                .append(classMissPct.toString().padStart(PCT_PAD_W)).append("%\n")
        }
        val pooledTotal = totalHits + totalMisses
        val overallMissPct = if (pooledTotal == 0L) 0L else totalMisses * PERCENT_SCALE / pooledTotal
        sb.append("  pooled hits=").append(totalHits)
            .append(" misses=").append(totalMisses)
            .append(" miss%=").append(overallMissPct)
            .append("  empties=").append(empties)
            .append("  huges=").append(huges).append('\n')
        return sb.toString()
    }

    companion object {
        private const val PERCENT_SCALE = 100
        private const val IDX_PAD_W = 4
        private const val SIZE_PAD_W = 12
        private const val COUNT_PAD_W = 14
        private const val PCT_PAD_W = 5
        private const val SIZE_PAD = "        " // padding between idx and size column
        private const val COUNT_PAD = "          " // padding between numeric columns

        /**
         * Creates a [PoolMissProfile] sized for the standard [PooledAllocator]
         * size-class ladder. External modules (e.g. `benchmark`) cannot construct
         * the internal [SizeClasses] directly, so this factory hides the
         * dependency while keeping the dimension in sync with the production
         * allocator's pool count.
         */
        fun forDefaultPool(): PoolMissProfile = PoolMissProfile(defaultPoolNSizes())

        /**
         * Resolves the standard size-class count used by every production
         * [PooledAllocator] instance. Mirrors the `SizeClasses` construction in
         * [PooledAllocator] so [forDefaultPool] stays in sync.
         */
        internal fun defaultPoolNSizes(): Int =
            SizeClasses(
                PooledAllocator.PAGE_SIZE,
                PooledAllocator.PAGE_SHIFTS,
                PooledAllocator.CHUNK_SIZE,
                directMemoryCacheAlignment = 0,
            ).nSizes

        /**
         * Translates a size-class index to its byte-size capacity using the
         * standard [PooledAllocator] ladder. Pass this to [format] when the
         * caller cannot reach the internal [SizeClasses] table (e.g. external
         * tooling rendering the histogram after a run).
         */
        fun defaultPoolSizeIdx2size(): (Int) -> Int {
            val sc = SizeClasses(
                PooledAllocator.PAGE_SIZE,
                PooledAllocator.PAGE_SHIFTS,
                PooledAllocator.CHUNK_SIZE,
                directMemoryCacheAlignment = 0,
            )
            return { sc.sizeIdx2size(it) }
        }
    }
}
