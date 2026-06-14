package io.github.fukusaka.keel.buf

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Direct contended bench on [ChunkArena.carve] / [PooledChunk.freeRun] without
 * the per-size-class freelist front.
 *
 * Complements [PooledAllocatorContendedBenchmark], which measures the full
 * `allocator.allocate(size) → release` path where the freelist absorbs the
 * steady state. This bench isolates the chunk-arena layer — the exact code
 * region that the planned thread-safety changes will guard — so the lock cost
 * is visible without freelist hits washing it out.
 *
 * Workload: each thread loops `arena.carve(sizeIdx) → buf.release()`. The
 * released buffer's `freeBacking` routes through [PooledChunk.freeRun], which
 * touches [PoolChunk.free] and (for subpage classes) [PoolSubpage.free]. Both
 * uniform-size and mixed-size scenarios are covered for parity with Bench 1.
 *
 * **Why this bench matters for the A vs B comparison**: the freelist front
 * already serialises pool hits via its own per-class lock, so adding an
 * arena-level mutex hardly shows in [PooledAllocatorContendedBenchmark]. The
 * carve / freeRun path is where Option A (single mutex per arena) and Option B
 * (Netty's per-size-class subpage head lock + arena chunk lock) actually
 * diverge — uniform-class contended runs are expected to be equivalent, while
 * mixed-class contended runs should show B's per-class parallelism.
 *
 * **Correctness**: the unsafe baseline is expected to corrupt under contention
 * (the exact reason for the upcoming fix). Per-thread exceptions are counted;
 * the harness reports them rather than failing the test, so the measurement
 * survives even when internal state is being trampled.
 *
 * Not a unit test — runs as `@Test` for jvmTest. Inspect stdout.
 *
 * Re-run: remove `@Ignore`, then
 *   ./gradlew :keel-io:jvmTest --tests "*ChunkArenaCarveBenchmark"
 */
@Ignore
class ChunkArenaCarveBenchmark {

    @Test
    fun uncontendedUniform() {
        println("Uncontended single-thread ChunkArena.carve+release per size class (JVM)")
        println("size|ns/op")
        for (size in UNIFORM_SIZES) {
            val ns = uncontendedTrial(intArrayOf(size))
            println("$size|${"%.2f".format(ns)}")
        }
        println("blackhole=$blackhole")
    }

    @Test
    fun uncontendedMixed() {
        println("Uncontended single-thread ChunkArena.carve+release, mixed-size rotation (JVM)")
        println("scenario|ns/op")
        val ns = uncontendedTrial(MIXED_SIZES)
        println("mixed|${"%.2f".format(ns)}")
        println("blackhole=$blackhole")
    }

    @Test
    fun contendedUniform() {
        println("Contended ChunkArena.carve+release, uniform size (JVM, java threads)")
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
        println("Contended ChunkArena.carve+release, mixed-size rotation (JVM, java threads)")
        println("scenario|threads|ns/op|Mops/sec|exceptions")
        for (n in THREAD_COUNTS) {
            val r = contendedTrial(MIXED_SIZES, n)
            println(formatTrial("mixed", n, r))
        }
        println("blackhole=$blackhole")
    }

    /**
     * Builds a fresh [ChunkArena] wired with [DirectIoBuf] backings + chunk
     * views — the same factories [PooledDirectAllocator] uses, so the bench
     * exercises the production carve path verbatim minus the freelist front.
     */
    @Suppress("IoBufLeak") // Trial-scoped — arena is discarded at trial end.
    private fun newArena(): ChunkArena {
        val sizeClasses = SizeClasses(
            PooledAllocator.PAGE_SIZE,
            PooledAllocator.PAGE_SHIFTS,
            PooledAllocator.CHUNK_SIZE,
            directMemoryCacheAlignment = 0,
        )
        return ChunkArena(
            sizeClasses = sizeClasses,
            newChunkBacking = { DirectIoBuf(PooledAllocator.CHUNK_SIZE) },
            newChunkView = ::directChunkView,
        )
    }

    /** Same [ChunkViewFactory] shape as [PooledDirectAllocator.newChunkView]. */
    private fun directChunkView(
        backing: IoBuf,
        byteOffset: Int,
        length: Int,
        pooledChunk: PooledChunk,
        handle: Long,
    ): IoBuf = DirectIoBuf.chunkView(backing, byteOffset, length, pooledChunk, handle)

    private fun uncontendedTrial(sizes: IntArray): Double {
        val arena = newArena()
        val sizeClasses = SizeClasses(
            PooledAllocator.PAGE_SIZE,
            PooledAllocator.PAGE_SHIFTS,
            PooledAllocator.CHUNK_SIZE,
            directMemoryCacheAlignment = 0,
        )
        val idxs = IntArray(sizes.size) { sizeClasses.size2SizeIdx(sizes[it]) }
        // Warm the chunks: the first carve allocates a backing chunk, so the
        // steady-state measurement skips the one-time chunk allocation cost.
        repeat(WARMUP_ITERS) {
            val idx = idxs[it % idxs.size]
            arena.carve(idx).release()
        }
        val samples = DoubleArray(SAMPLES)
        for (t in 0 until SAMPLES) {
            val start = System.nanoTime()
            var i = 0
            while (i < TRIAL_ITERS) {
                val idx = idxs[i % idxs.size]
                arena.carve(idx).release()
                i++
            }
            samples[t] = (System.nanoTime() - start).toDouble() / TRIAL_ITERS
            blackhole += i.toLong()
        }
        samples.sort()
        // Best-effort teardown: a corrupt arena may throw on reclaim. The
        // measurement is already in `samples`, so swallow to keep the harness
        // running across multiple trials in the same JVM.
        runCatching { arena.close() }
        return samples[SAMPLES / 2]
    }

    private fun contendedTrial(sizes: IntArray, nThreads: Int): TrialResult {
        val arena = newArena()
        val sizeClasses = SizeClasses(
            PooledAllocator.PAGE_SIZE,
            PooledAllocator.PAGE_SHIFTS,
            PooledAllocator.CHUNK_SIZE,
            directMemoryCacheAlignment = 0,
        )
        val idxs = IntArray(sizes.size) { sizeClasses.size2SizeIdx(sizes[it]) }
        // Warm the arena so the wall-clock isolates contention cost from the
        // initial chunk-backing allocation.
        repeat(WARMUP_ITERS) {
            val idx = idxs[it % idxs.size]
            arena.carve(idx).release()
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
                        val idx = idxs[(tid + i) % idxs.size]
                        arena.carve(idx).release()
                        ops++
                        i++
                    }
                } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
                    // Intentionally swallowed: same unsafe-baseline rationale as
                    // [PooledAllocatorContendedBenchmark]. The carve / freeRun path
                    // is even more prone to corruption than the freelist front, so
                    // expect exception counts to dominate here under the unsafe
                    // baseline — that itself is data the comparison records.
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
        runCatching { arena.close() }
        return TrialResult(wallNs, totalOps, exceptionCount.get())
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

        /** 256 = subpage tier, 8192 = page tier. */
        val UNIFORM_SIZES = intArrayOf(256, 8192)

        /** Mixed-size rotation covering subpage + page tiers. */
        val MIXED_SIZES = intArrayOf(16, 64, 256, 1024, 4096, 8192)

        val THREAD_COUNTS = intArrayOf(2, 4, 8, 16)

        const val WARMUP_ITERS = 100_000
        const val TRIAL_ITERS = 2_000_000
        const val ITERS_PER_THREAD = 1_000_000
        const val SAMPLES = 5
    }
}
