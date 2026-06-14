package io.github.fukusaka.keel.buf

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * Concurrent correctness regression for the Option B Netty-style lock topology
 * on [PooledAllocator]: arena lock + per-size-class subpage head locks. Verifies
 * that off-EL parallel `allocate → write → release` cycles do not corrupt the
 * arena's chunk list, subpage chain, or freelist, and that the platform locks'
 * own lifecycle (`PlatformLock.close`) survives a teardown with no in-flight
 * allocations.
 *
 * Three scenarios:
 *
 * 1. **uniform-class contention** — every thread allocates the same small size
 *    class. Stresses the per-class subpage head lock; alloc and release on the
 *    same chain serialise on this one lock. A pre-Option-B race in alloc / free
 *    would surface here as either an exception (corrupt chain pointers) or a
 *    leak (the final freelist drain finds the wrong count).
 *
 * 2. **mixed-class parallelism** — every thread rotates through six size
 *    classes spanning subpage + page tiers. Stresses the per-class
 *    parallelism: different classes proceed simultaneously under their own
 *    head locks. A wrong-lock release (e.g. the Netty PR #13626 pre-fix
 *    behaviour) would mis-lock the head and corrupt a different class's chain.
 *
 * 3. **chunked release order** — alloc-all-then-release-all batches force a
 *    state where the chain is populated, then drained. Catches releases that
 *    take wrong locks when the subpage is the last live one in its class.
 *
 * Run as part of `:keel-io:jvmTest` — these are correctness tests, not
 * benchmarks; they MUST not be `@Ignore`'d. They are deliberately short
 * (1-2 s per scenario) so the gate stays cheap.
 */
class PooledAllocatorConcurrentTest {

    @Test
    fun `concurrent uniform-class allocate and release does not corrupt`() {
        val allocator = arbitraryConcurrencyAllocator()
        try {
            runConcurrent(allocator, sizes = intArrayOf(SIZE_SUBPAGE), threads = 8, iters = ITERS)
            // The freelist should hold at most slotCap entries for this class; the
            // exact final count is not deterministic (the trim pass may evict), but
            // the test passes if no exception fired during the workload.
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
    fun `concurrent mixed-class workload exercises per-class parallelism`() {
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
                            // Allocate a batch (chain populates), then release the
                            // whole batch (chain drains). This sequence stresses the
                            // path where a subpage becomes the last live one in the
                            // chain and the release must take both arena and head
                            // locks under the correct ordering.
                            for (i in 0 until batchSize) {
                                val sz = MIXED_SIZES[(tid + round + i) % MIXED_SIZES.size]
                                bufs += allocator.allocate(sz).also { it.writeByte((tid and 0xFF).toByte()) }
                            }
                            for (b in bufs) b.release()
                            bufs.clear()
                        }
                    } catch (@Suppress("SwallowedException", "TooGenericExceptionCaught") t: Throwable) {
                        // A real race shows up either as a CheckException from a
                        // null subpage / wrong head, or a pthread errno from a
                        // corrupted mutex. Both bubble up here.
                        exc.incrementAndGet()
                    }
                }
            }
            workers.forEach { it.start() }
            workers.forEach { it.join() }
            assertEquals(0, exc.get(), "concurrent batched workload triggered $exc exceptions")
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
