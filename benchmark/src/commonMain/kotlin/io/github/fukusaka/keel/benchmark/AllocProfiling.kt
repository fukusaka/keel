package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.AllocationProfile
import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.buf.withProfiling

/**
 * Shared allocation-size profile for `--profile-alloc` benchmark runs.
 *
 * Phase 0 of the chunk-based allocator roadmap: a `--profile-alloc` run wraps
 * the engine allocator with a [io.github.fukusaka.keel.buf.ProfilingAllocator]
 * that records every `allocate()` request size into this one shared profile
 * (aggregating across all EventLoops), then the entry point dumps the
 * histogram periodically. The distribution quantifies how much traffic falls
 * outside the registered exact pool size (the 8 KiB read buffer) — i.e. how
 * much currently bypasses pooling — to ground the size-class / chunk-size
 * decisions with measured data.
 */
val benchmarkAllocationProfile = AllocationProfile()

/**
 * The engine allocator, wrapped with a shared profiling decorator when
 * [BenchmarkConfig.profileAlloc] is set (`--profile-alloc`); otherwise the
 * plain platform default. Off-path for normal benchmark runs.
 */
fun benchmarkAllocator(config: BenchmarkConfig): BufferAllocator =
    if (config.profileAlloc) defaultAllocator().withProfiling(benchmarkAllocationProfile) else defaultAllocator()
