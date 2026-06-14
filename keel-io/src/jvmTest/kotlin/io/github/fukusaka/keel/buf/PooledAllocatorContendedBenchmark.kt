package io.github.fukusaka.keel.buf

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Contended allocate-then-release benchmark for the full [PooledAllocator]
 * pipeline (size-class round-up + freelist front + chunk-arena back-end).
 *
 * Drives N java threads doing `allocate(size) → writeByte → release` roundtrips
 * against one shared [PooledAllocator] instance. The per-size-class [Freelist]
 * absorbs the steady-state path, so the chunk-arena layer is exercised mostly at
 * cold start and on cache eviction — this measures the **production hot path**,
 * including the cost of the planned thread-safety changes on the freelist-hit
 * fast path.
 *
 * Two workload scenarios capture the gap between cross-thread funnel stress
 * (one allocator pounded on a single size class) and production-shaped
 * mixed-size traffic:
 * - **uniform**: every iteration uses one size class (e.g. 256 B subpage, 8 KiB
 *   page). Matches a worst-case cross-thread allocate pattern where one Channel
 *   allocator is pounded by N off-EL coroutines on one size class.
 * - **mixed**: every iteration rotates through six size classes spanning
 *   subpage and page tiers. Models production HTTP server traffic, where header
 *   / chunked-body / WS-frame allocations span many size classes simultaneously
 *   — the regime where the upcoming Netty-style per-size-class subpage lock
 *   (Option B) is expected to outperform a single arena-wide mutex (Option A).
 *
 * **What this bench does not measure**: the chunk-arena cold path (carve /
 * freeRun) directly. The freelist absorbs the steady state, so once warmed up
 * every allocate is a pop and every release is a push. For cold-path numbers
 * see `ChunkArenaCarveBenchmark`.
 *
 * **Correctness**: each iteration writes one byte to the returned buffer so the
 * JIT cannot elide the allocate / release pair. Exceptions in any thread are
 * counted; the **unsafe baseline** (current `main`) is expected to corrupt or
 * crash under contention — that is the data we want for the comparison
 * baseline, not a test failure.
 *
 * Not a unit test — runs as `@Test` for jvmTest, no functional assertion. Inspect
 * stdout for ns/op + Mops/sec + exception count.
 *
 * Re-run: remove `@Ignore`, then
 *   ./gradlew :keel-io:jvmTest --tests "*PooledAllocatorContendedBenchmark"
 */
// Decision-aid measurement: kept @Ignore so CI / regression gates do not
// burn ~30 seconds on a bench whose only assertion is "harness ran". The
// captured numbers from each run land in the corresponding PR description.
@Ignore
class PooledAllocatorContendedBenchmark {

    @Test
    fun uncontendedUniform() {
        println("Uncontended single-thread allocate+release per size class (JVM)")
        println("size|ns/op")
        for (size in UNIFORM_SIZES) {
            val ns = uncontendedTrial(intArrayOf(size))
            println("$size|${"%.2f".format(ns)}")
        }
        println("blackhole=$blackhole")
    }

    @Test
    fun uncontendedMixed() {
        println("Uncontended single-thread allocate+release, mixed-size rotation (JVM)")
        println("scenario|ns/op")
        val ns = uncontendedTrial(MIXED_SIZES)
        println("mixed|${"%.2f".format(ns)}")
        println("blackhole=$blackhole")
    }

    @Test
    fun contendedUniform() {
        println("Contended allocate+release, uniform size (JVM, java threads)")
        println("size|threads|ns/op|Mops/sec|exceptions")
        for (size in UNIFORM_SIZES) {
            for (n in THREAD_COUNTS) {
                val r = contendedTrial(intArrayOf(size), n)
                println(formatTrial(size.toString(), n, r))
            }
        }
        println("blackhole=$blackhole")
    }

    @Test
    fun contendedMixed() {
        println("Contended allocate+release, mixed-size rotation (JVM, java threads)")
        println("scenario|threads|ns/op|Mops/sec|exceptions")
        for (n in THREAD_COUNTS) {
            val r = contendedTrial(MIXED_SIZES, n)
            println(formatTrial("mixed", n, r))
        }
        println("blackhole=$blackhole")
    }

    private fun uncontendedTrial(sizes: IntArray): Double {
        val allocator = PooledDirectAllocator()
        try {
            // Warmup: prime the per-size-class freelist so the steady-state
            // measurement starts on a hot pool (pop + push) rather than amortising
            // the first-touch carve into the trial average.
            repeat(WARMUP_ITERS) {
                val s = sizes[it % sizes.size]
                val buf = allocator.allocate(s)
                buf.writeByte(0)
                buf.release()
            }
            val samples = DoubleArray(SAMPLES)
            for (t in 0 until SAMPLES) {
                val start = System.nanoTime()
                var i = 0
                while (i < TRIAL_ITERS) {
                    val s = sizes[i % sizes.size]
                    val buf = allocator.allocate(s)
                    buf.writeByte(0)
                    buf.release()
                    i++
                }
                samples[t] = (System.nanoTime() - start).toDouble() / TRIAL_ITERS
                blackhole += i.toLong()
            }
            samples.sort()
            return samples[SAMPLES / 2]
        } finally {
            allocator.close()
        }
    }

    private fun contendedTrial(sizes: IntArray, nThreads: Int): TrialResult {
        val allocator = PooledDirectAllocator()
        try {
            // Hot-pool warmup parallels [uncontendedTrial]: the per-size-class
            // freelist must already hold buffers when the worker threads start, so
            // the wall-clock isolates contention cost from cold-start cost.
            repeat(WARMUP_ITERS) {
                val s = sizes[it % sizes.size]
                val buf = allocator.allocate(s)
                buf.writeByte(0)
                buf.release()
            }
            val perThreadOps = LongArray(nThreads)
            val exceptionCount = AtomicInteger(0)
            val threads = ArrayList<Thread>(nThreads)
            val start = System.nanoTime()
            for (t in 0 until nThreads) {
                val tid = t
                threads += Thread {
                    var ops = 0L
                    try {
                        var i = 0
                        while (i < ITERS_PER_THREAD) {
                            // Offset the rotation per thread so each thread enters
                            // a different size class first; this avoids artificially
                            // serialising the same freelist on cycle 0.
                            val s = sizes[(tid + i) % sizes.size]
                            val buf = allocator.allocate(s)
                            buf.writeByte(0)
                            buf.release()
                            ops++
                            i++
                        }
                    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
                        // Intentionally swallowed: the unsafe-baseline measurement is
                        // expected to corrupt state and throw under contention. The
                        // counter is the diagnostic, not the throwable — failing the
                        // worker thread would terminate the trial mid-measurement and
                        // discard data the comparison needs.
                        exceptionCount.incrementAndGet()
                    }
                    perThreadOps[tid] = ops
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            val wallNs = (System.nanoTime() - start).toDouble()
            var totalOps = 0L
            for (o in perThreadOps) totalOps += o
            blackhole += totalOps
            return TrialResult(wallNs, totalOps, exceptionCount.get())
        } finally {
            // close() drains the freelists and releases chunk backings. Wrapping
            // in runCatching mirrors the unsafe-baseline reality: a corrupt
            // freelist may throw on close, but the bench's own measurement is
            // already complete by then.
            runCatching { allocator.close() }
        }
    }

    private fun formatTrial(label: String, threads: Int, r: TrialResult): String {
        val nsPerOp = if (r.totalOps > 0) r.wallNs / r.totalOps else 0.0
        val mops = if (r.wallNs > 0) r.totalOps / (r.wallNs / 1e9) / 1e6 else 0.0
        return "$label|$threads|${"%.2f".format(nsPerOp)}|${"%.2f".format(mops)}|${r.exceptions}"
    }

    private class TrialResult(val wallNs: Double, val totalOps: Long, val exceptions: Int)

    private companion object {
        @Volatile
        @JvmStatic
        var blackhole: Long = 0

        /** Sizes for the uniform scenario. 256 = subpage tier, 8192 = page tier. */
        val UNIFORM_SIZES = intArrayOf(256, 8192)

        /** Rotated through every iteration in the mixed scenario; spans subpage + page tiers. */
        val MIXED_SIZES = intArrayOf(16, 64, 256, 1024, 4096, 8192)

        val THREAD_COUNTS = intArrayOf(2, 4, 8, 16)

        const val WARMUP_ITERS = 200_000
        const val TRIAL_ITERS = 2_000_000
        const val ITERS_PER_THREAD = 1_000_000
        const val SAMPLES = 5
    }
}
