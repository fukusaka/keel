package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.AllocationProfile
import io.github.fukusaka.keel.buf.BufferAllocator
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
 * The engine allocator. When [BenchmarkConfig.profileAlloc] is set
 * (`--profile-alloc`):
 * 1. The underlying [defaultAllocator] is constructed with
 *    [benchmarkPoolMissProfile] so the pool dispatch records its path on every
 *    `allocate()` call.
 * 2. The result is then wrapped with a [io.github.fukusaka.keel.buf.ProfilingAllocator]
 *    so the request-size histogram is captured at the public boundary.
 *
 * Off-path for normal benchmark runs (no decorator, no profile field reads).
 */
fun benchmarkAllocator(config: BenchmarkConfig): BufferAllocator =
    if (config.profileAlloc) {
        defaultAllocator(benchmarkPoolMissProfile).withProfiling(benchmarkAllocationProfile)
    } else {
        defaultAllocator()
    }
