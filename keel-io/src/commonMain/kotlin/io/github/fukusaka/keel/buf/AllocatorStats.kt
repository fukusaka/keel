package io.github.fukusaka.keel.buf

/**
 * Pull-shape snapshot view of a [BufferAllocator]'s state. Complement to the
 * push hooks ([BufferAllocatorStatsCounter] / [BufferAllocatorLifecycleListener])
 * for telemetry adapters that prefer to read current values on a collection
 * cycle rather than receive per-event callbacks.
 *
 * Cross-library precedent: Netty `PoolArenaMetric` + `PoolSubpageMetric`, Tokio
 * `RuntimeMetrics`, HikariCP `PoolStats`, jemalloc `mallctl stats.*`. All four
 * expose snapshot-shaped getters alongside (or instead of) push callbacks,
 * because Micrometer / OT `ObservableUpDownCounter` / Prometheus scrape all
 * poll current values at collection time.
 *
 * **Two-layer structure**:
 * - **Allocator-level config** ([poolName], [sizeClasses], [slotCaps],
 *   [chunkSize]) is constant for the allocator's lifetime; adapters cache it
 *   once and never re-fetch.
 * - **Current state + cumulative counters** ([snapshot]) is captured at call
 *   time. The returned [AllocatorStatsSnapshot] is immutable and self-contained.
 *
 * **Snapshot cadence**: typically collection cycle (~15s for OT default).
 * Hot-path callers should use [BufferAllocatorStatsCounter] instead.
 *
 * **Default**: [BufferAllocator.stats] returns a [NoOpAllocatorStats] singleton
 * for stateless allocators (DefaultAllocator on JVM/Native, JS); pool-based
 * allocators override to expose real values.
 */
interface AllocatorStats {
    /**
     * Stable allocator identifier — becomes the `pool.name` attribute on OT
     * metrics. Defaults to the implementation class's simple name for
     * convenience; user-facing wiring should set a meaningful pool name when
     * multiple allocator instances coexist.
     */
    val poolName: String

    /**
     * Size class capacities at each index `[0, classCount)`. Element `i` is
     * the byte capacity of class `i`. Constant for the allocator's lifetime.
     */
    val sizeClasses: IntArray

    /**
     * Per-class pool slot caps. Element `i` is the maximum number of pooled
     * buffers retained for class `i`. Constant for the allocator's lifetime
     * (modulo [BufferAllocator.hintSizeClass] adjustments at startup).
     */
    val slotCaps: IntArray

    /**
     * Chunk back-end allocation unit (typically `PooledAllocator.CHUNK_SIZE`,
     * 256 KiB). Constant for the allocator's lifetime.
     */
    val chunkSize: Int

    /**
     * Captures a point-in-time snapshot of cumulative counters and current
     * pool state. The returned snapshot is immutable; per-class detail is
     * exposed via zero-alloc indexed accessors on [AllocatorStatsSnapshot].
     */
    fun snapshot(): AllocatorStatsSnapshot
}

/**
 * Immutable snapshot of [AllocatorStats]. Aggregate counters are stored as
 * scalar fields; per-class detail is accessed through zero-alloc indexed
 * accessors so OT `ObservableUpDownCounter` callbacks can iterate
 * `0 until classCount` without per-iteration allocation.
 *
 * Snapshot construction allocates: the snapshot object itself plus its
 * internal `LongArray` / `IntArray` views. At collection-cycle cadence
 * (~15s) this is negligible (a few hundred bytes per snapshot).
 *
 * **Internal constructor**: snapshots are produced by [AllocatorStats.snapshot]
 * implementations; user code never constructs one directly.
 */
@Suppress("LongParameterList") // Snapshot intentionally aggregates all OT-bound fields in one immutable record.
class AllocatorStatsSnapshot internal constructor(
    /** Cumulative count of [BufferAllocator.allocate] calls (weight-scaled). */
    val cumulativeAllocations: Long,
    /** Cumulative count of releases (weight-scaled). */
    val cumulativeReleases: Long,
    /** Cumulative bytes returned by allocate (sum of byteSize × weight). */
    val cumulativeAllocBytes: Long,
    /** Cumulative bytes released. */
    val cumulativeReleaseBytes: Long,
    /** Cumulative pool-hit count (path = HIT). */
    val cumulativeHits: Long,
    /** Cumulative pool-miss count (path = MISS, chunk-arena carve). */
    val cumulativeMisses: Long,
    /** Cumulative `allocate(0)` (path = EMPTY) count. */
    val cumulativeEmpty: Long,
    /** Cumulative huge-allocation (path = HUGE) count. */
    val cumulativeHuge: Long,
    /** Cumulative count of releases that returned to the pool. */
    val cumulativePooled: Long,
    /** Cumulative count of releases discarded because the freelist was full. */
    val cumulativeDiscarded: Long,
    /** Cumulative count of releases routed straight to free (huge / closed). */
    val cumulativeFreed: Long,
    /** Cumulative chunk-arena chunk allocations. */
    val cumulativeChunksAllocated: Long,
    /** Cumulative chunk-arena chunk frees. */
    val cumulativeChunksFreed: Long,
    /** Currently resident chunks in the arena. */
    val residentChunks: Int,
    /** Whether [BufferAllocator.close] has been called. */
    val isClosed: Boolean,
    /** Number of size classes. Equals `AllocatorStats.sizeClasses.size`. */
    val classCount: Int,
    private val perClassHits: LongArray,
    private val perClassMisses: LongArray,
    private val perClassAllocations: LongArray,
    private val perClassReleases: LongArray,
    private val perClassCachedCount: IntArray,
    private val perClassSizes: IntArray,
    private val perClassTiers: Array<SizeTier>,
) {
    /** Per-class cumulative hits at index [classIdx] (0 until [classCount]). */
    fun classCumulativeHits(classIdx: Int): Long = perClassHits[classIdx]

    /** Per-class cumulative misses at index [classIdx]. */
    fun classCumulativeMisses(classIdx: Int): Long = perClassMisses[classIdx]

    /** Per-class cumulative allocations at index [classIdx]. */
    fun classCumulativeAllocations(classIdx: Int): Long = perClassAllocations[classIdx]

    /** Per-class cumulative releases at index [classIdx]. */
    fun classCumulativeReleases(classIdx: Int): Long = perClassReleases[classIdx]

    /** Currently cached buffers at index [classIdx]. */
    fun classCachedCount(classIdx: Int): Int = perClassCachedCount[classIdx]

    /** Size in bytes of class at index [classIdx]. */
    fun classSize(classIdx: Int): Int = perClassSizes[classIdx]

    /** Bucket [SizeTier] of class at index [classIdx]. */
    fun sizeTier(classIdx: Int): SizeTier = perClassTiers[classIdx]
}

/**
 * Default [AllocatorStats] for allocators that do not track state — stateless
 * [DefaultAllocator] on JVM/Native, JS implementations. All counters report
 * zero; `classCount` is zero so adapters that iterate `0 until classCount`
 * become no-ops.
 */
object NoOpAllocatorStats : AllocatorStats {
    override val poolName: String = "no-op"
    override val sizeClasses: IntArray = IntArray(0)
    override val slotCaps: IntArray = IntArray(0)
    override val chunkSize: Int = 0

    private val emptySnapshot = AllocatorStatsSnapshot(
        cumulativeAllocations = 0,
        cumulativeReleases = 0,
        cumulativeAllocBytes = 0,
        cumulativeReleaseBytes = 0,
        cumulativeHits = 0,
        cumulativeMisses = 0,
        cumulativeEmpty = 0,
        cumulativeHuge = 0,
        cumulativePooled = 0,
        cumulativeDiscarded = 0,
        cumulativeFreed = 0,
        cumulativeChunksAllocated = 0,
        cumulativeChunksFreed = 0,
        residentChunks = 0,
        isClosed = false,
        classCount = 0,
        perClassHits = LongArray(0),
        perClassMisses = LongArray(0),
        perClassAllocations = LongArray(0),
        perClassReleases = LongArray(0),
        perClassCachedCount = IntArray(0),
        perClassSizes = IntArray(0),
        perClassTiers = emptyArray(),
    )

    override fun snapshot(): AllocatorStatsSnapshot = emptySnapshot
}

/**
 * Coarse-grained size class bucket for OT attribute labelling. Maps onto
 * [PooledAllocator]'s internal slot-cap tiers (TINY / PAGE / LARGE) plus an
 * uncached HUGE bucket. Mirrors the `db.client.connection.state` / `jvm.memory.type`
 * convention of using a string-enum attribute for pool classification (verified
 * 2 independent OT primary sources: JVM semconv + Micrometer NettyAllocatorMetrics).
 *
 * Boundary values mirror the public constants in [PooledAllocator]:
 *
 * - [TINY] — `byteSize <= TINY_CLASS_MAX` (~512 B; subpage tier).
 * - [PAGE] — `byteSize <= PAGE_SIZE` (~8 KiB; page tier, includes the standard
 *   8 KiB read-buffer class).
 * - [LARGE] — `byteSize <= MAX_CACHED_CAPACITY` (~32 KiB; cached but coarser
 *   slot cap).
 * - [HUGE] — `byteSize > MAX_CACHED_CAPACITY`; uncached, direct allocation.
 *
 * The vocabulary intentionally differs from Netty's `small / normal / huge`
 * (3 tiers): keel retains TINY because the TINY slot cap (16) differs
 * meaningfully from the PAGE cap (8), and tier-level dashboards benefit from
 * distinguishing the two.
 */
enum class SizeTier {
    TINY,
    PAGE,
    LARGE,
    HUGE,
    ;

    companion object {
        /** Upper bound (inclusive) of the [TINY] tier in bytes. */
        const val TINY_MAX_BYTES: Int = 512

        /** Upper bound (inclusive) of the [PAGE] tier in bytes. */
        const val PAGE_MAX_BYTES: Int = 8192

        /** Upper bound (inclusive) of the [LARGE] tier in bytes. */
        const val LARGE_MAX_BYTES: Int = 32 * 1024

        /**
         * Classifies a byte size into a [SizeTier] bucket. Used at allocator
         * construction to pre-compute the tier for each size class index, so
         * the hot path resolves tier by indexed lookup rather than a chain of
         * `when` branches.
         */
        fun fromBytes(byteSize: Int): SizeTier = when {
            byteSize <= TINY_MAX_BYTES -> TINY
            byteSize <= PAGE_MAX_BYTES -> PAGE
            byteSize <= LARGE_MAX_BYTES -> LARGE
            else -> HUGE
        }
    }
}
