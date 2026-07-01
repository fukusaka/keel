package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.engine.netty.nettyByteBufAllocator

/**
 * JVM allocator selection for benchmarks. `--allocator=netty` routes an engine
 * that consumes `IoEngineConfig.allocator` (the NIO engine) through Netty's
 * `PooledByteBufAllocator` — a comparison baseline for keel's own
 * `PooledDirectAllocator`. Anything else uses [benchmarkAllocator] (keel).
 *
 * The Netty allocator is used raw: Netty manages its own arenas and metrics, so
 * keel's `--profile-alloc` / `--profile-xthread` decorators apply only to the
 * keel allocator. GC capture (`BENCH_GC_CAPTURE`, jstat) is allocator-agnostic
 * and works for both, which is the axis the keel-vs-Netty A/B compares.
 */
fun benchmarkAllocatorFor(config: BenchmarkConfig): BufferAllocator =
    if (config.allocatorImpl == "netty") nettyByteBufAllocator() else benchmarkAllocator(config)
