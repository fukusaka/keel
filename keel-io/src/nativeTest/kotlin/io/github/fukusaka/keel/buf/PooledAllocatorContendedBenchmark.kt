package io.github.fukusaka.keel.buf

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.plus
import kotlinx.cinterop.staticCFunction
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar
import kotlin.concurrent.AtomicInt
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Native counterpart of the JVM `PooledAllocatorContendedBenchmark`. Drives N
 * pthread workers doing `allocate(size) → writeByte → release` roundtrips
 * against one shared [SlabAllocator] (the native pool allocator). The intent,
 * scenarios (uniform / mixed), and output format match the JVM version so a
 * before/after comparison between allocator freelist variants reads
 * identically across platforms.
 *
 * **Why the JVM bench is not enough**: the unsafe baseline races are sensitive
 * to memory model + scheduler. The native side uses `SpinLockFreelist` and
 * direct `pthread_create`, so the contention shape can differ from the JVM
 * (where `TreiberStackFreelist` + `ForkJoinPool` workers run). Both targets
 * are production hosts (M1 macOS + a 32-core Ryzen linuxX64 host), so both must be measured.
 *
 * Pthread is used directly (mirroring `ContendedFreelistBench`) rather than
 * Kotlin/Native `Worker`s, to keep the harness close to the native engines'
 * own threading model (raw pthread, no Worker overhead).
 *
 * Re-run: remove `@Ignore`, then
 *   ./gradlew :keel-io:nativeTest --tests "*PooledAllocatorContendedBenchmark"
 */
@OptIn(ExperimentalForeignApi::class)
@Ignore
class PooledAllocatorContendedBenchmark {

    @Test
    fun uncontendedUniform() {
        println("Uncontended single-thread allocate+release per size class (Native)")
        println("size|ns/op")
        for (size in UNIFORM_SIZES) {
            val ns = uncontendedTrial(intArrayOf(size))
            println("$size|${fmt(ns)}")
        }
        println("blackhole=${blackhole.value}")
    }

    @Test
    fun uncontendedMixed() {
        println("Uncontended single-thread allocate+release, mixed-size rotation (Native)")
        println("scenario|ns/op")
        val ns = uncontendedTrial(MIXED_SIZES)
        println("mixed|${fmt(ns)}")
        println("blackhole=${blackhole.value}")
    }

    @Test
    fun contendedUniform() {
        println("Contended allocate+release, uniform size (Native, raw pthread)")
        println("size|threads|ns/op|Mops/sec|exceptions")
        for (size in UNIFORM_SIZES) {
            for (n in THREAD_COUNTS) {
                val r = contendedTrial(intArrayOf(size), n)
                println(formatTrial(size.toString(), n, r))
            }
        }
        println("blackhole=${blackhole.value}")
    }

    @Test
    fun contendedMixed() {
        println("Contended allocate+release, mixed-size rotation (Native, raw pthread)")
        println("scenario|threads|ns/op|Mops/sec|exceptions")
        for (n in THREAD_COUNTS) {
            val r = contendedTrial(MIXED_SIZES, n)
            println(formatTrial("mixed", n, r))
        }
        println("blackhole=${blackhole.value}")
    }

    private fun uncontendedTrial(sizes: IntArray): Double {
        val allocator = SlabAllocator()
        try {
            // Warmup primes the per-size-class freelist; the steady-state samples
            // then measure the production hot path (pop + push), not the carve cost.
            repeat(WARMUP_ITERS) {
                val s = sizes[it % sizes.size]
                val buf = allocator.allocate(s)
                buf.writeByte(0)
                buf.release()
            }
            val samples = DoubleArray(SAMPLES)
            for (t in 0 until SAMPLES) {
                val mark = TimeSource.Monotonic.markNow()
                var i = 0
                while (i < TRIAL_ITERS) {
                    val s = sizes[i % sizes.size]
                    val buf = allocator.allocate(s)
                    buf.writeByte(0)
                    buf.release()
                    i++
                }
                samples[t] = mark.elapsedNow().inWholeNanoseconds.toDouble() / TRIAL_ITERS
                blackhole.value += i
            }
            samples.sort()
            return samples[SAMPLES / 2]
        } finally {
            allocator.close()
        }
    }

    private fun contendedTrial(sizes: IntArray, nThreads: Int): TrialResult {
        val allocator = SlabAllocator()
        try {
            // Hot-pool warmup before the parallel section so the wall-clock isolates
            // contention from first-touch carve cost.
            repeat(WARMUP_ITERS) {
                val s = sizes[it % sizes.size]
                val buf = allocator.allocate(s)
                buf.writeByte(0)
                buf.release()
            }

            val exceptionCount = AtomicInt(0)
            val arena = Arena()
            val refs = ArrayList<StableRef<ThreadArg>>(nThreads)
            val wallNs: Double
            try {
                val threads = arena.allocArray<pthread_tVar>(nThreads)
                val mark = TimeSource.Monotonic.markNow()
                for (tid in 0 until nThreads) {
                    val arg = ThreadArg(allocator, sizes, tid, exceptionCount)
                    val ref = StableRef.create(arg)
                    refs += ref
                    pthread_create(
                        (threads + tid.toLong())!!,
                        null,
                        staticCFunction { rawArg ->
                            val a = rawArg!!.asStableRef<ThreadArg>().get()
                            a.run()
                            null
                        },
                        ref.asCPointer(),
                    )
                }
                for (i in 0 until nThreads) pthread_join(threads[i], null)
                wallNs = mark.elapsedNow().inWholeNanoseconds.toDouble()
            } finally {
                arena.clear()
            }

            var totalOps = 0L
            for (ref in refs) {
                totalOps += ref.get().ops
                ref.dispose()
            }
            blackhole.value += totalOps.toInt()
            return TrialResult(wallNs, totalOps, exceptionCount.value)
        } finally {
            // close() may throw if the unsafe baseline left the freelist or chunk
            // arena in a corrupt state; swallow so the harness can continue to the
            // next trial with a fresh allocator.
            runCatching { allocator.close() }
        }
    }

    private fun formatTrial(label: String, threads: Int, r: TrialResult): String {
        val nsPerOp = if (r.totalOps > 0) r.wallNs / r.totalOps else 0.0
        val mops = if (r.wallNs > 0) r.totalOps.toDouble() / (r.wallNs / 1e9) / 1e6 else 0.0
        return "$label|$threads|${fmt(nsPerOp)}|${fmt(mops)}|${r.exceptions}"
    }

    private class ThreadArg(
        private val allocator: SlabAllocator,
        private val sizes: IntArray,
        private val tid: Int,
        private val exceptionCount: AtomicInt,
    ) {
        var ops: Long = 0
            private set

        fun run() {
            try {
                var i = 0
                while (i < ITERS_PER_THREAD) {
                    // Per-thread offset spreads the cycle-0 starting class across
                    // the rotation so threads do not all hit the same freelist on
                    // their first iteration.
                    val s = sizes[(tid + i) % sizes.size]
                    val buf = allocator.allocate(s)
                    buf.writeByte(0)
                    buf.release()
                    ops++
                    i++
                }
            } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
                // Intentionally swallowed under the unsafe baseline: a corrupt
                // freelist or chunk-arena state throws on subsequent operations.
                // The counter is the diagnostic; aborting the worker would
                // destroy the wall-clock the bench is collecting.
                // Atomic CAS loop for thread-safe increment across worker threads;
                // a plain `value++` would race and undercount under genuine MT load.
                while (true) {
                    val cur = exceptionCount.value
                    if (exceptionCount.compareAndSet(cur, cur + 1)) break
                }
            }
        }
    }

    private class TrialResult(val wallNs: Double, val totalOps: Long, val exceptions: Int)

    private companion object {
        // AtomicInt because Native test target lacks the JVM `@Volatile` long
        // semantics; an int counter is enough to defeat dead-code elimination.
        val blackhole = AtomicInt(0)

        /** 256 = subpage tier, 8192 = page tier. */
        val UNIFORM_SIZES = intArrayOf(256, 8192)

        /** Mixed-size rotation covering subpage + page tiers. */
        val MIXED_SIZES = intArrayOf(16, 64, 256, 1024, 4096, 8192)

        val THREAD_COUNTS = intArrayOf(2, 4, 8, 16)

        const val WARMUP_ITERS = 200_000
        const val TRIAL_ITERS = 2_000_000
        const val ITERS_PER_THREAD = 1_000_000
        const val SAMPLES = 5

        fun fmt(v: Double): String =
            (kotlin.math.round(v * 100.0) / 100.0).toString()
    }
}
