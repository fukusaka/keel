@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

import kotlin.concurrent.atomics.AtomicLong

/**
 * Histogram of [BufferAllocator.allocate] request sizes, bucketed by
 * ceil-log2 so the distribution across size ranges is captured with
 * bounded memory and thread-safe (atomic) counters.
 *
 * Bucket `k` (for `k >= 1`) counts requests whose size falls in
 * `(2^(k-1), 2^k]`; bucket 0 counts sizes `<= 1`. The default 8 KiB read
 * buffer lands in bucket 13 (`(4096, 8192]`); TLS records (~16 KiB) in
 * bucket 14; a 100 KiB `/large` response body in bucket 17 (`(64K, 128K]`).
 *
 * **Purpose (Phase 0 of the chunk allocator roadmap)**: validate how much
 * allocation traffic falls outside the registered exact pool size(s) — i.e.
 * how much currently bypasses pooling into fresh `malloc`/`free`. Combined
 * with the registered size set, the histogram answers "does variable-size
 * traffic bypass the cache, and at what sizes" to ground the size-class /
 * chunk-size decisions.
 *
 * **Thread safety**: thread-safe via per-bucket [AtomicLong]; a single
 * profile instance may be shared across per-EventLoop allocators (see
 * [ProfilingAllocator.createForEventLoop]) so all EventLoops aggregate into
 * one histogram.
 */
class AllocationProfile {
    private val buckets = Array(NUM_BUCKETS) { AtomicLong(0) }

    /** Records one allocation of [size] bytes into the matching bucket. */
    fun record(size: Int) {
        buckets[bucketOf(size)].fetchAndAdd(1)
    }

    /** Returns a point-in-time copy of all bucket counts. */
    fun snapshot(): LongArray = LongArray(NUM_BUCKETS) { buckets[it].load() }

    /** Total number of recorded allocations across all buckets. */
    fun total(): Long {
        var sum = 0L
        for (b in buckets) sum += b.load()
        return sum
    }

    /** Resets all bucket counts to zero. */
    fun reset() {
        for (b in buckets) b.store(0)
    }

    /**
     * Renders the histogram as a human-readable multi-line table: one row
     * per non-empty bucket with its inclusive upper bound, count, and
     * percentage of total. Intended for benchmark / profiling dumps.
     */
    fun format(): String {
        val snap = snapshot()
        val total = snap.sum()
        if (total == 0L) return "AllocationProfile: (no allocations recorded)"
        val sb = StringBuilder()
        sb.append("AllocationProfile: total=").append(total).append(" allocations\n")
        for (k in snap.indices) {
            val count = snap[k]
            if (count == 0L) continue
            val pct = count * PERCENT_SCALE / total
            sb.append("  <= ").append(bucketUpperBound(k).toString().padStart(BOUND_PAD))
                .append("  ").append(count.toString().padStart(COUNT_PAD))
                .append("  ").append(pct).append("%\n")
        }
        return sb.toString()
    }

    companion object {
        /** Bucket count: covers sizes up to 2^32 plus the `<= 1` bucket. */
        const val NUM_BUCKETS = 33

        private const val PERCENT_SCALE = 100
        private const val BOUND_PAD = 12
        private const val COUNT_PAD = 10

        /**
         * Maps [size] to its ceil-log2 bucket index: bucket 0 for `size <= 1`,
         * otherwise the smallest `k` with `size <= 2^k`. Clamped to the
         * available bucket range.
         */
        fun bucketOf(size: Int): Int =
            if (size <= 1) 0 else (Int.SIZE_BITS - (size - 1).countLeadingZeroBits()).coerceIn(0, NUM_BUCKETS - 1)

        /** Inclusive upper bound of bucket [k] (`2^k`, or 1 for bucket 0). */
        fun bucketUpperBound(k: Int): Long = if (k == 0) 1L else 1L shl k
    }
}

/**
 * Delegating [BufferAllocator] that records every [allocate] request size
 * into a shared [AllocationProfile], then delegates unchanged.
 *
 * Off by default in production: only wrap the engine allocator when
 * profiling (e.g. a benchmark `--profile-alloc` flag). The size histogram
 * is captured at the public [allocate] boundary, so it is engine-agnostic
 * and sees exactly what consumers request (before any pool round-up).
 *
 * [createForEventLoop] shares the **same** [profile] with the per-EventLoop
 * child, so all EventLoops aggregate into one histogram.
 *
 * **Thread safety**: the shared [profile] is thread-safe; the decorator
 * itself holds no mutable state.
 *
 * @param delegate The underlying allocator to delegate to.
 * @param profile The shared histogram; defaults to a fresh instance.
 */
class ProfilingAllocator(
    private val delegate: BufferAllocator,
    val profile: AllocationProfile = AllocationProfile(),
) : BufferAllocator {

    override fun allocate(capacity: Int): IoBuf {
        profile.record(capacity)
        return delegate.allocate(capacity)
    }

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
        delegate.wrapBytes(bytes, offset, length)

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        delegate.slice(source, offset, length)

    override fun registerPoolSize(size: Int, maxSlots: Int) {
        delegate.registerPoolSize(size, maxSlots)
    }

    override fun createForEventLoop(): BufferAllocator =
        ProfilingAllocator(delegate.createForEventLoop(), profile)
}

/**
 * Wraps this allocator with [ProfilingAllocator], recording allocate
 * request sizes into [profile] (a fresh [AllocationProfile] by default).
 *
 * Returns the [ProfilingAllocator] so callers can read [ProfilingAllocator.profile].
 */
fun BufferAllocator.withProfiling(profile: AllocationProfile = AllocationProfile()): ProfilingAllocator =
    ProfilingAllocator(this, profile)
