package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.AllocationProfile
import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.CrossThreadReleaseProfile
import io.github.fukusaka.keel.buf.NoOpStatsCounter
import io.github.fukusaka.keel.buf.PoolMissProfile
import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.buf.withProfiling

/**
 * Shared allocation-size profile for `--profile-alloc` benchmark runs.
 *
 * A `--profile-alloc` run wraps the engine allocator with a
 * [io.github.fukusaka.keel.buf.ProfilingAllocator] that records every
 * `allocate()` request size into this one shared profile (aggregating across
 * all EventLoops), then the entry point dumps the histogram periodically. The
 * distribution quantifies how much traffic falls outside the registered exact
 * pool size (the 8 KiB read buffer) — i.e. how much currently bypasses pooling
 * — to ground the size-class / chunk-size decisions with measured data.
 *
 * Captured at the **public** [BufferAllocator.allocate] boundary, before pool
 * round-up. Use with [benchmarkPoolMissProfile] to also see what the pool does
 * with those requests (hit / miss / huge bypass).
 */
val benchmarkAllocationProfile = AllocationProfile()

/**
 * Shared pool-dispatch profile for `--profile-alloc` benchmark runs. Captured
 * **inside** the pool — see [PoolMissProfile] for the path taxonomy. Pairs
 * with [benchmarkAllocationProfile]:
 *
 * - The size histogram answers "what sizes does production traffic ask for?"
 * - The miss profile answers "what does the pool do with those requests?" —
 *   how often the chunk-arena fires (the region the thread-safety work guards),
 *   and whether misses concentrate in one size class or spread across many
 *   (the data point that informs single-mutex vs per-size-class-lock choice).
 *
 * Allocated lazily so a benchmark.kexe binary that never sets `--profile-alloc`
 * pays no atomic-array cost at startup.
 */
val benchmarkPoolMissProfile: PoolMissProfile by lazy { PoolMissProfile.forDefaultPool() }

/**
 * Shared cross-thread release-rate profile for `--profile-xthread` benchmark
 * runs. Wired as the allocator's lifecycle listener so it sees every pooled
 * allocate / release, and records — per size class — the fraction of buffers
 * released on a different thread than they were allocated on. The entry point
 * dumps it periodically. Answers "which size classes actually fall to
 * cross-thread release?" so a sharded-central + MPSC-return allocator can be
 * scoped to exactly those classes.
 *
 * Allocated lazily so a benchmark.kexe binary that never sets `--profile-xthread`
 * pays no cost at startup.
 */
val benchmarkCrossThreadProfile: CrossThreadReleaseProfile by lazy { CrossThreadReleaseProfile.forDefaultPool() }

/**
 * The engine allocator. The two profiling flags wire independent channels:
 * - [BenchmarkConfig.profileAlloc] (`--profile-alloc`): the [benchmarkPoolMissProfile]
 *   stats counter records the pool dispatch path, and a
 *   [io.github.fukusaka.keel.buf.ProfilingAllocator] decorator captures the
 *   request-size histogram at the public boundary.
 * - [BenchmarkConfig.profileXthread] (`--profile-xthread`): the
 *   [benchmarkCrossThreadProfile] lifecycle listener records per-class
 *   cross-thread release rates.
 *
 * The flags compose (both may be set). Off-path for normal benchmark runs (no
 * decorator, no profile field reads).
 */
fun benchmarkAllocator(config: BenchmarkConfig): BufferAllocator {
    val base = when {
        config.profileAlloc && config.profileXthread ->
            defaultAllocator(benchmarkPoolMissProfile, benchmarkCrossThreadProfile)
        config.profileXthread ->
            defaultAllocator(NoOpStatsCounter, benchmarkCrossThreadProfile)
        config.profileAlloc ->
            defaultAllocator(benchmarkPoolMissProfile)
        else ->
            defaultAllocator()
    }
    return if (config.profileAlloc) base.withProfiling(benchmarkAllocationProfile) else base
}
