@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

import kotlin.concurrent.atomics.AtomicLong

/**
 * Per-size-class **cross-thread release-rate** profiler for [PooledAllocator]:
 * an opt-in [BufferAllocatorLifecycleListener] that measures, per size class,
 * the fraction of buffers released on a thread other than the one that
 * allocated them.
 *
 * **What it answers.** keel allocates a buffer with `buf.owner` capturing the
 * allocating EventLoop child's `returnToPool`; [AbstractIoBuf.release] then runs
 * that owner on **whichever thread** drove the refcount to zero. A buffer
 * allocated on EventLoop A but released on a `Dispatchers.Default` worker is a
 * *cross-thread release*. That fraction differs sharply by size class — an 8 KiB
 * read buffer is read → process → write → released all on its EventLoop (~0%),
 * while a TLS-record or WS-message buffer handed to application code can be
 * released off-EventLoop (>0%). This profile turns that into measured per-class
 * rates so a sharded-central + MPSC-return-queue allocator can be scoped to
 * exactly the classes that need it, rather than applied uniformly.
 *
 * **Why a listener, not a hot-path counter.** Telling alloc-thread from
 * free-thread needs a thread-id read on the release path; on Apple/arm64 a
 * `pthread_self` + compare is ~17 ns, too costly to leave on the production hot
 * path for a one-shot fact-finding measurement. As a
 * [BufferAllocatorLifecycleListener] the cost is paid only while the profile is
 * attached, and only at the per-buffer lifecycle boundary (the listener fire
 * site), never per byte.
 *
 * **Apple/GCD caveat.** NWConnection runs an EventLoop on a GCD serial queue
 * that migrates across worker pthreads, so a logically same-EventLoop release
 * can observe a different [currentThreadId] and be **over-counted** as
 * cross-thread. This is a false positive, never a false negative — it can only
 * make a class look like it needs sharding when it does not, which is the safe
 * direction for the "does this class need the next-stage allocator" decision.
 * pthread-pinned EventLoops (kqueue / epoll / io_uring / NIO) report truthfully.
 *
 * **Scope.** Only buffers that flow through [PooledAllocator]'s allocate /
 * returnToPool fire `onAllocated` / `onReleased` here, so the measurement covers
 * the pooled Native engines (kqueue / epoll / io_uring) and the JVM
 * `PooledDirect` allocator. Engine-direct buffers (`NettyByteBufIoBuf`,
 * `RingBufferIoBuf`) bypass keel pooling and are not counted.
 *
 * **close() guard.** [AbstractIoBuf.close] frees its backing directly and
 * bypasses `owner.release`, so it never fires `onReleased`. A buffer torn down
 * that way leaves its [onAllocated] map entry orphaned. An `onReleased` that
 * finds no entry is tallied as [droppedReleases] and skipped, never
 * mis-attributed; a growing [pendingAllocations] flags a close()-heavy path.
 *
 * **Thread safety.** The per-class counters are [AtomicLong] arrays (mirroring
 * [PoolMissProfile]); the alloc-thread map is a platform thread-safe map. One
 * instance may be shared across every per-EventLoop [PooledAllocator] child so
 * all EventLoops aggregate into one set of per-class rates.
 *
 * @param nSizes number of size classes in the standard ladder; the counter
 *   arrays are `nSizes + 1` so the final slot collects huge (> chunk) buffers.
 * @param size2Idx maps an [IoBuf] capacity to its size-class index (or `nSizes`
 *   for huge); supply [PoolMissProfile.defaultPoolSize2Idx].
 * @param sizeIdx2size maps a size-class index back to its byte capacity for
 *   rendering; supply [PoolMissProfile.defaultPoolSizeIdx2size].
 */
class CrossThreadReleaseProfile(
    private val nSizes: Int,
    private val size2Idx: (Int) -> Int,
    private val sizeIdx2size: (Int) -> Int,
) : BufferAllocatorLifecycleListener {
    private val allocThreads = XthreadMap()
    private val totalReleases: Array<AtomicLong> = Array(nSizes + 1) { AtomicLong(0) }
    private val crossThreadReleases: Array<AtomicLong> = Array(nSizes + 1) { AtomicLong(0) }
    private val dropped = AtomicLong(0)

    /** Records the allocating thread for [buf] (the thread calling `allocate`). */
    override fun onAllocated(buf: IoBuf) {
        allocThreads.put(buf, currentThreadId())
    }

    /**
     * Records a release: looks up [buf]'s allocating thread, and if it differs
     * from the current (freeing) thread, increments the cross-thread counter for
     * [buf]'s size class. A release with no recorded allocation (close() bypass)
     * is counted in [droppedReleases] and skipped.
     */
    override fun onReleased(buf: IoBuf) {
        val freeTid = currentThreadId()
        val allocTid = allocThreads.remove(buf)
        if (allocTid == NO_ALLOC_THREAD) {
            dropped.fetchAndAdd(1)
            return
        }
        val idx = size2Idx(buf.capacity).coerceIn(0, nSizes)
        totalReleases[idx].fetchAndAdd(1)
        if (allocTid != freeTid) {
            crossThreadReleases[idx].fetchAndAdd(1)
        }
    }

    /** Number of [onReleased] calls with no matching [onAllocated] entry (see close() guard). */
    fun droppedReleases(): Long = dropped.load()

    /**
     * Live entries in the alloc-thread map. Trends to zero on a healthy path; a
     * persistently growing value flags a leak or a close()-heavy workload that
     * orphans entries (each such teardown is also tallied nowhere on release —
     * cross-check against [droppedReleases]).
     */
    fun pendingAllocations(): Int = allocThreads.size

    /** Point-in-time per-class total release counts (index `nSizes` = huge). */
    fun totalReleasesSnapshot(): LongArray = LongArray(nSizes + 1) { totalReleases[it].load() }

    /** Point-in-time per-class cross-thread release counts (index `nSizes` = huge). */
    fun crossThreadReleasesSnapshot(): LongArray = LongArray(nSizes + 1) { crossThreadReleases[it].load() }

    /** Resets all counters to zero (does not clear the in-flight alloc-thread map). */
    fun reset() {
        for (i in 0..nSizes) {
            totalReleases[i].store(0)
            crossThreadReleases[i].store(0)
        }
        dropped.store(0)
    }

    /**
     * Renders the profile as a human-readable multi-line table: one row per size
     * class that saw a release, plus a summary footer with the overall
     * cross-thread ratio (the headline number for "which classes need the
     * next-stage sharded allocator"). The final `huge` row aggregates buffers
     * above the chunk size.
     */
    fun format(): String {
        val totalSnap = totalReleasesSnapshot()
        val xtSnap = crossThreadReleasesSnapshot()
        var observed = 0L
        for (i in 0..nSizes) observed += totalSnap[i]
        val droppedCount = dropped.load()
        if (observed == 0L && droppedCount == 0L) {
            return "CrossThreadReleaseProfile: (no releases recorded)"
        }
        val sb = StringBuilder()
        sb.append("CrossThreadReleaseProfile: total=").append(observed)
            .append(" releases, dropped=").append(droppedCount)
            .append(", pending=").append(allocThreads.size).append('\n')
        sb.append("  idx").append(SIZE_PAD).append("size")
            .append(COUNT_PAD).append("total")
            .append(COUNT_PAD).append("xthread")
            .append("  xthread%\n")
        var grandTotal = 0L
        var grandXt = 0L
        for (i in 0..nSizes) {
            val t = totalSnap[i]
            val x = xtSnap[i]
            if (t == 0L) continue
            grandTotal += t
            grandXt += x
            val pct = x * PERCENT_SCALE / t
            val sizeLabel = if (i == nSizes) "huge" else sizeIdx2size(i).toString()
            sb.append("  ").append(i.toString().padStart(IDX_PAD_W))
                .append(sizeLabel.padStart(SIZE_PAD_W))
                .append(t.toString().padStart(COUNT_PAD_W))
                .append(x.toString().padStart(COUNT_PAD_W))
                .append(pct.toString().padStart(PCT_PAD_W)).append("%\n")
        }
        val overall = if (grandTotal == 0L) 0L else grandXt * PERCENT_SCALE / grandTotal
        sb.append("  overall total=").append(grandTotal)
            .append(" xthread=").append(grandXt)
            .append(" xthread%=").append(overall).append('\n')
        return sb.toString()
    }

    companion object {
        /** Sentinel returned by [XthreadMap.remove] when the buffer has no recorded alloc thread. */
        internal const val NO_ALLOC_THREAD: Long = -1L

        private const val PERCENT_SCALE = 100
        private const val IDX_PAD_W = 4
        private const val SIZE_PAD_W = 12
        private const val COUNT_PAD_W = 14
        private const val PCT_PAD_W = 5
        private const val SIZE_PAD = "        " // padding between idx and size column
        private const val COUNT_PAD = "          " // padding between numeric columns

        /**
         * Creates a [CrossThreadReleaseProfile] sized for the standard
         * [PooledAllocator] ladder. External modules (e.g. `benchmark`) cannot
         * construct the internal [SizeClasses] directly, so this factory wires the
         * size↔index bridges from [PoolMissProfile] while keeping the dimension in
         * sync with the production allocator.
         */
        fun forDefaultPool(): CrossThreadReleaseProfile =
            CrossThreadReleaseProfile(
                nSizes = PoolMissProfile.defaultPoolNSizes(),
                size2Idx = PoolMissProfile.defaultPoolSize2Idx(),
                sizeIdx2size = PoolMissProfile.defaultPoolSizeIdx2size(),
            )
    }
}

/**
 * Returns an opaque, stable identifier for the OS thread currently executing —
 * used by [CrossThreadReleaseProfile] to tell the allocating thread from the
 * releasing thread. Values are only compared for equality, never interpreted.
 *
 * - JVM: `Thread.currentThread().threadId()`.
 * - Native: `pthread_self()` reinterpreted to `Long`.
 * - JS: a constant (single execution context), so no release is ever
 *   cross-thread.
 */
internal expect fun currentThreadId(): Long

/**
 * A platform thread-safe identity map from an [IoBuf] (by reference identity) to
 * the thread id that allocated it. Written by the allocating thread ([put]) and
 * the freeing thread ([remove]) concurrently, so the actual must be thread-safe.
 *
 * - JVM: `ConcurrentHashMap` ([IoBuf] uses identity hashCode).
 * - Native: an identity `HashMap` guarded by a [NativeMutex] (single mutex — the
 *   listener fire site is off the per-byte hot path).
 * - JS: a plain `HashMap` (single-threaded, no lock — same rationale as
 *   `ArenaLock`'s JS no-op).
 */
internal expect class XthreadMap() {
    /** Records [threadId] as the allocating thread for [buf]. */
    fun put(buf: IoBuf, threadId: Long)

    /**
     * Removes and returns the allocating thread id for [buf], or
     * [CrossThreadReleaseProfile.NO_ALLOC_THREAD] if none was recorded.
     */
    fun remove(buf: IoBuf): Long

    /** Number of live (allocated-but-not-yet-released) entries. */
    val size: Int
}
