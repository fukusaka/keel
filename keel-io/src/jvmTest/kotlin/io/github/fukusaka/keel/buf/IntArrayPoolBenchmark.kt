package io.github.fukusaka.keel.buf

import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Measures per-cycle JVM allocation for [IntArrayPool] borrow/recycle.
 *
 * Mirrors the format of
 * [io.github.fukusaka.keel.engine.netty.NettyReadPathAllocationBenchmark]:
 * `ThreadMXBean.getThreadAllocatedBytes` delta over `ITERS` iterations,
 * median of `TRIALS` trials.
 *
 * Three paths:
 *
 * - **A (cold pool)**: each `borrow` allocates a fresh `IntArray`, no
 *   recycle. Floor for the case where the pool has been bypassed (e.g.
 *   high churn / over-borrow). One `IntArray(arraySize)` allocation
 *   per iteration.
 * - **B (warm pool, single)**: borrow + recycle the same array each
 *   iteration. Pool steady state. Should be near-zero per iteration
 *   (the `fill(emptySentinel)` on borrow is in-place).
 * - **C (warm pool, depth 8)**: borrow / borrow / borrow / .../ recycle
 *   in batches of 8 to exercise the freelist FIFO motion. Floor for
 *   "pool size > 1" workloads.
 *
 * Not a unit test — runs as `@Test` for the normal `jvmTest` task;
 * inspect stdout. Does not assert.
 */
class IntArrayPoolBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean
    private val pool = IntArrayPool(arraySize = ARRAY_SIZE, maxPooled = 128)

    private fun measureBytesPerIter(iterations: Int, body: () -> Unit): Long {
        repeat(WARMUP) { body() }
        val tid = Thread.currentThread().threadId()
        val start = tmx.getThreadAllocatedBytes(tid)
        repeat(iterations) { body() }
        val end = tmx.getThreadAllocatedBytes(tid)
        return (end - start) / iterations
    }

    /** A: bypass pool — always allocate fresh. */
    private fun pathA() {
        val arr = IntArray(ARRAY_SIZE) { -1 }
        // touch to prevent dead-code elimination
        if (arr[0] != -1) error("unreachable")
    }

    /** B: warm pool, depth 1 — borrow + recycle the same array. */
    private fun pathB() {
        val arr = pool.borrow()
        if (arr[0] != -1) error("unreachable")
        pool.recycle(arr)
    }

    /** C: warm pool, depth 8 — borrow 8 then recycle 8. */
    private val depthCArrays = Array<IntArray?>(8) { null }
    private fun pathC() {
        for (i in 0 until 8) depthCArrays[i] = pool.borrow()
        for (i in 0 until 8) pool.recycle(depthCArrays[i]!!)
    }

    @Test
    fun `IntArrayPool borrow recycle allocation`() {
        val medA = LongArray(TRIALS) { measureBytesPerIter(ITERS, ::pathA) }
            .also { it.sort() }[TRIALS / 2]
        val medB = LongArray(TRIALS) { measureBytesPerIter(ITERS, ::pathB) }
            .also { it.sort() }[TRIALS / 2]
        val medC = LongArray(TRIALS) { measureBytesPerIter(ITERS / 8, ::pathC) }
            .also { it.sort() }[TRIALS / 2]

        println("=== IntArrayPool allocation (bytes / cycle, arraySize=$ARRAY_SIZE, iters=$ITERS × $TRIALS) ===")
        println("  A (no pool, fresh IntArray each)      median=$medA bytes / iter")
        println("  B (pool, depth 1 borrow+recycle)      median=$medB bytes / iter")
        println("  C (pool, depth 8 borrow×8+recycle×8)  median=${medC / 8} bytes / iter (batch-normalised)")
    }

    companion object {
        // 128 headers × 6 ints = 768, the planned default for HttpHeadersMap-equivalent slot table
        private const val ARRAY_SIZE = 768
        private const val WARMUP = 2_000
        private const val ITERS = 10_000
        private const val TRIALS = 5
    }
}
