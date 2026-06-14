package io.github.fukusaka.keel.buf

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Concurrent correctness regression for the Option A single-mutex lock topology
 * on [PooledAllocator]: a single arena lock guards every mutation of the chunk
 * back-end (chunk list, run / subpage carve, run / subpage release, chunk
 * reclaim). Verifies that off-EL parallel `allocate → write → release` cycles
 * do not corrupt the chunk arena, per-chunk subpage chains, or freelists, and
 * that the platform lock's own lifecycle (`PlatformLock.close`) survives a
 * teardown with no in-flight allocations.
 *
 * Four scenarios:
 *
 * 1. **uniform subpage class** — every thread allocates the same small size
 *    class. Stresses the subpage allocate / free path through the single
 *    arena lock; a missing lock acquire would surface as either a
 *    [CheckException] from a corrupted subpage chain or a freelist count drift
 *    visible at teardown.
 *
 * 2. **uniform page-tier class** — every thread allocates the same page-tier
 *    size. Stresses the run allocate / free path through the same lock.
 *
 * 3. **mixed-class workload** — every thread rotates through six size classes
 *    spanning subpage + page tiers. Stresses serialised contention on the
 *    single mutex across heterogeneous size classes — the worst case for
 *    Option A throughput, but a correctness baseline against B's per-class
 *    parallelism.
 *
 * 4. **batched alloc-then-release** — allocate a batch, then release the whole
 *    batch. Drives chains from "many partially-free" to "fully drained" so
 *    the subpage-final-element → run-reclaim transition is exercised under
 *    contention.
 *
 * Run as part of `:keel-io:jvmTest` — these are correctness tests, not
 * benchmarks; they MUST not be `@Ignore`'d. Each scenario completes in ~1 s
 * on the JVM so the gate stays cheap.
 */
class PooledAllocatorConcurrentTest {

    @Test
    fun `concurrent uniform-class allocate and release does not corrupt`() {
        val allocator = arbitraryConcurrencyAllocator()
        try {
            runConcurrent(allocator, sizes = intArrayOf(SIZE_SUBPAGE), threads = 8, iters = ITERS)
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `concurrent uniform-class on a page-tier size does not corrupt`() {
        val allocator = arbitraryConcurrencyAllocator()
        try {
            runConcurrent(allocator, sizes = intArrayOf(SIZE_PAGE), threads = 8, iters = ITERS)
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `concurrent mixed-class workload through single arena lock`() {
        val allocator = arbitraryConcurrencyAllocator()
        try {
            runConcurrent(allocator, sizes = MIXED_SIZES, threads = 8, iters = ITERS)
        } finally {
            allocator.close()
        }
    }

    @Test
    fun `concurrent batched alloc-then-release across many threads`() {
        val allocator = arbitraryConcurrencyAllocator()
        try {
            val threads = 8
            val batchSize = 64
            val rounds = 50
            val exc = AtomicInteger(0)
            val workers = ArrayList<Thread>(threads)
            for (t in 0 until threads) {
                val tid = t
                workers += Thread {
                    try {
                        val bufs = ArrayList<IoBuf>(batchSize)
                        repeat(rounds) { round ->
                            for (i in 0 until batchSize) {
                                val sz = MIXED_SIZES[(tid + round + i) % MIXED_SIZES.size]
                                bufs += allocator.allocate(sz).also { it.writeByte((tid and 0xFF).toByte()) }
                            }
                            for (b in bufs) b.release()
                            bufs.clear()
                        }
                    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
                        exc.incrementAndGet()
                    }
                }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join() }
            assertEquals(0, exc.get(), "concurrent batched workload triggered ${exc.get()} exceptions")
        } finally {
            allocator.close()
        }
    }

    /**
     * Constructs an allocator wired with [MutexFreelist] for **arbitrary
     * concurrency**. The default [PooledDirectAllocator] uses the intrusive
     * `TreiberStackFreelist`, which is ABA-unsafe under genuine MPMC (verified
     * by `FreelistContendedBenchmark`); it is safe in production only because
     * keel engines are EL-pinned and never truly contend the freelist. This
     * test simulates the public `Channel.allocator` from off-EL contract, so
     * it picks the freelist matching that contract.
     */
    private fun arbitraryConcurrencyAllocator(): PooledAllocator =
        PooledDirectAllocator(freelistFactory = ::MutexFreelist)

    private fun runConcurrent(
        allocator: PooledAllocator,
        sizes: IntArray,
        threads: Int,
        iters: Int,
    ) {
        val exc = AtomicInteger(0)
        val firstException = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val workers = ArrayList<Thread>(threads)
        for (t in 0 until threads) {
            val tid = t
            workers += Thread {
                try {
                    var i = 0
                    while (i < iters) {
                        val sz = sizes[(tid + i) % sizes.size]
                        val buf = allocator.allocate(sz)
                        buf.writeByte((tid and 0xFF).toByte())
                        buf.release()
                        i++
                    }
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    firstException.compareAndSet(null, t)
                    exc.incrementAndGet()
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join() }
        if (exc.get() != 0) {
            val first = firstException.get()
            fail("concurrent workload triggered ${exc.get()} exceptions; first: ${first?.message ?: first?.javaClass?.simpleName}\n${first?.stackTraceToString()}")
        }
    }

    private companion object {
        /** Subpage size class (small tier). */
        const val SIZE_SUBPAGE = 256

        /** Page-tier size class (the engine read buffer size). */
        const val SIZE_PAGE = 8192

        /** Mixed-size rotation covering subpage + page tiers. */
        val MIXED_SIZES = intArrayOf(16, 64, 256, 1024, 4096, 8192)

        /**
         * Iterations per worker. Kept modest so the test runs in ~1 s on the JVM —
         * correctness regressions surface well within this budget; longer runs
         * are the benchmark's job, not the gate's.
         */
        const val ITERS = 50_000
    }
}
