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
 * Native counterpart of the JVM `ChunkArenaCarveBenchmark`. Drives
 * `arena.carve(sizeIdx) → release` directly, bypassing the per-size-class
 * freelist front so the chunk-arena layer's lock cost is visible without
 * freelist hits washing it out.
 *
 * Wires the native carve path verbatim from [SlabAllocator]: a `nativeHeap`
 * chunk backing + [NativeIoBuf.chunkView] for the run-binding. Threading uses
 * raw pthread (mirroring `ContendedFreelistBench`), so the harness sits as
 * close to the engines' own threading model as possible.
 *
 * Re-run: remove `@Ignore`, then
 *   ./gradlew :keel-io:nativeTest --tests "*ChunkArenaCarveBenchmark"
 */
@OptIn(ExperimentalForeignApi::class)
@Ignore
class ChunkArenaCarveBenchmark {

    @Test
    fun uncontendedUniform() {
        println("Uncontended single-thread ChunkArena.carve+release per size class (Native)")
        println("size|ns/op")
        for (size in UNIFORM_SIZES) {
            val ns = uncontendedTrial(intArrayOf(size))
            println("$size|${fmt(ns)}")
        }
        println("blackhole=${blackhole.value}")
    }

    @Test
    fun uncontendedMixed() {
        println("Uncontended single-thread ChunkArena.carve+release, mixed-size rotation (Native)")
        println("scenario|ns/op")
        val ns = uncontendedTrial(MIXED_SIZES)
        println("mixed|${fmt(ns)}")
        println("blackhole=${blackhole.value}")
    }

    @Test
    fun contendedUniform() {
        println("Contended ChunkArena.carve+release, uniform size (Native, raw pthread)")
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
        println("Contended ChunkArena.carve+release, mixed-size rotation (Native, raw pthread)")
        println("scenario|threads|ns/op|Mops/sec|exceptions")
        for (n in THREAD_COUNTS) {
            val r = contendedTrial(MIXED_SIZES, n)
            println(formatTrial("mixed", n, r))
        }
        println("blackhole=${blackhole.value}")
    }

    /** Wires the same factories `SlabAllocator` uses, minus the freelist front. */
    @Suppress("IoBufLeak") // Trial-scoped; the arena is discarded at trial end.
    private fun newArena(): ChunkArena {
        val sizeClasses = SizeClasses(
            PooledAllocator.PAGE_SIZE,
            PooledAllocator.PAGE_SHIFTS,
            PooledAllocator.CHUNK_SIZE,
            directMemoryCacheAlignment = 0,
        )
        return ChunkArena(
            sizeClasses = sizeClasses,
            newChunkBacking = { NativeIoBuf(PooledAllocator.CHUNK_SIZE) },
            newChunkView = ::nativeChunkView,
        )
    }

    private fun nativeChunkView(
        backing: IoBuf,
        byteOffset: Int,
        length: Int,
        pooledChunk: PooledChunk,
        handle: Long,
    ): IoBuf = NativeIoBuf.chunkView(backing, byteOffset, length, pooledChunk, handle)

    private fun sizeIdxs(sizes: IntArray): IntArray {
        val sizeClasses = SizeClasses(
            PooledAllocator.PAGE_SIZE,
            PooledAllocator.PAGE_SHIFTS,
            PooledAllocator.CHUNK_SIZE,
            directMemoryCacheAlignment = 0,
        )
        return IntArray(sizes.size) { sizeClasses.size2SizeIdx(sizes[it]) }
    }

    private fun uncontendedTrial(sizes: IntArray): Double {
        val arena = newArena()
        val idxs = sizeIdxs(sizes)
        repeat(WARMUP_ITERS) {
            val idx = idxs[it % idxs.size]
            arena.carve(idx).release()
        }
        val samples = DoubleArray(SAMPLES)
        for (t in 0 until SAMPLES) {
            val mark = TimeSource.Monotonic.markNow()
            var i = 0
            while (i < TRIAL_ITERS) {
                val idx = idxs[i % idxs.size]
                arena.carve(idx).release()
                i++
            }
            samples[t] = mark.elapsedNow().inWholeNanoseconds.toDouble() / TRIAL_ITERS
            blackhole.value += i
        }
        samples.sort()
        runCatching { arena.close() }
        return samples[SAMPLES / 2]
    }

    private fun contendedTrial(sizes: IntArray, nThreads: Int): TrialResult {
        val arena = newArena()
        val idxs = sizeIdxs(sizes)
        repeat(WARMUP_ITERS) {
            val idx = idxs[it % idxs.size]
            arena.carve(idx).release()
        }

        val exceptionCount = AtomicInt(0)
        val cArena = Arena()
        val refs = ArrayList<StableRef<ThreadArg>>(nThreads)
        val wallNs: Double
        try {
            val threads = cArena.allocArray<pthread_tVar>(nThreads)
            val mark = TimeSource.Monotonic.markNow()
            for (tid in 0 until nThreads) {
                val arg = ThreadArg(arena, idxs, tid, exceptionCount)
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
            cArena.clear()
        }

        var totalOps = 0L
        for (ref in refs) {
            totalOps += ref.get().ops
            ref.dispose()
        }
        blackhole.value += totalOps.toInt()
        runCatching { arena.close() }
        return TrialResult(wallNs, totalOps, exceptionCount.value)
    }

    private fun formatTrial(label: String, threads: Int, r: TrialResult): String {
        val nsPerOp = if (r.totalOps > 0) r.wallNs / r.totalOps else 0.0
        val mops = if (r.wallNs > 0) r.totalOps.toDouble() / (r.wallNs / 1e9) / 1e6 else 0.0
        return "$label|$threads|${fmt(nsPerOp)}|${fmt(mops)}|${r.exceptions}"
    }

    private class ThreadArg(
        private val arena: ChunkArena,
        private val idxs: IntArray,
        private val tid: Int,
        private val exceptionCount: AtomicInt,
    ) {
        var ops: Long = 0
            private set

        fun run() {
            try {
                var i = 0
                while (i < ITERS_PER_THREAD) {
                    val idx = idxs[(tid + i) % idxs.size]
                    arena.carve(idx).release()
                    ops++
                    i++
                }
            } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
                // Intentionally swallowed: the unsafe baseline corrupts arena
                // state under contention. The counter records the failure rate;
                // failing the worker would discard the bench's timing data.
                while (true) {
                    val cur = exceptionCount.value
                    if (exceptionCount.compareAndSet(cur, cur + 1)) break
                }
            }
        }
    }

    private class TrialResult(val wallNs: Double, val totalOps: Long, val exceptions: Int)

    private companion object {
        val blackhole = AtomicInt(0)

        /** 256 = subpage tier, 8192 = page tier. */
        val UNIFORM_SIZES = intArrayOf(256, 8192)

        /** Mixed-size rotation covering subpage + page tiers. */
        val MIXED_SIZES = intArrayOf(16, 64, 256, 1024, 4096, 8192)

        val THREAD_COUNTS = intArrayOf(2, 4, 8, 16)

        const val WARMUP_ITERS = 100_000
        const val TRIAL_ITERS = 2_000_000
        const val ITERS_PER_THREAD = 1_000_000
        const val SAMPLES = 5

        fun fmt(v: Double): String =
            (kotlin.math.round(v * 100.0) / 100.0).toString()
    }
}
