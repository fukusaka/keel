@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Concurrency regression test for the shared [ChunkArena]'s **subpage** carve
 * path under multiple EventLoops, complementing [SharedChunkArenaConcurrencyTest]
 * (which exercises an 8 KiB **run** class with cross-thread release).
 *
 * Reproduces the crash a multi-EventLoop WebSocket binary-echo workload surfaced:
 * holding pooled `BinaryChunks` deplete the per-EventLoop freelist, so almost
 * every allocate is a pool miss that hits `chunkArena.carve` → `carveSubpage`.
 * With several EventLoops missing the small (256 B subpage) class at once, the
 * shared arena's subpage/run bookkeeping corrupts and a later carve trips
 * `PoolChunk.allocateRun`'s free-run validation (`IllegalStateException:
 * runsAvail[..] returned handle .. with non-zero bitmapIdx — low-bits corruption`,
 * a subpage handle leaking into the run free-queue).
 *
 * Differences from [SharedChunkArenaConcurrencyTest] that close the gap:
 * - **subpage class** (256 B) not a run class (8 KiB) — exercises `carveSubpage`.
 * - **same-thread release** (each worker frees what it carved) — the observed
 *   workload had a 0 % cross-thread-release rate; the corruption comes from the
 *   concurrent carve, not from cross-thread return.
 * - **held batches** larger than the class freelist slot cap, so the overflow
 *   forces a steady stream of concurrent `carveSubpage` rather than freelist hits.
 *
 * Pass criteria: no worker observes an exception across all iterations.
 *
 * JVM-only by design (uses `java.lang.Thread`); the arena logic under test lives
 * in commonMain and the Native `ArenaLock` actual shares [MutexFreelist]'s proven
 * `pthread_mutex` lifecycle, so this seam covers the shared-arena subpage contract
 * for both platforms.
 */
class SharedArenaSubpageConcurrentCarveTest {

    @Test
    fun `concurrent same-thread carve and release of a subpage class on a shared arena stays consistent`() {
        val root = PooledDirectAllocator()
        // Per-EventLoop children that all share the root's chunk arena.
        val children = Array(WORKERS) { root.createChild() }
        val errors = AtomicInteger(0)
        val firstError = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)

        val workers = Array(WORKERS) { tid ->
            Thread {
                val child = children[tid]
                val held = ArrayList<IoBuf>(BATCH)
                try {
                    start.await()
                    repeat(ITERATIONS) {
                        // Hold a batch larger than the subpage class's freelist slot
                        // cap so the overflow forces concurrent carveSubpage on the
                        // shared arena instead of being served from the local cache.
                        repeat(BATCH) { held.add(child.allocate(SUBPAGE_CLASS_SIZE)) }
                        // Same-thread release: the observed workload returned every
                        // buffer on its owning EventLoop (0 % cross-thread).
                        for (i in held.indices) held[i].release()
                        held.clear()
                    }
                } catch (t: Throwable) {
                    firstError.compareAndSet(null, t)
                    errors.incrementAndGet()
                }
            }
        }

        workers.forEach { it.start() }
        start.countDown()
        workers.forEach { it.join(JOIN_BUDGET_MS) }
        root.close()

        assertEquals(0, errors.get(), "a worker observed an exception: ${firstError.get()}")
    }

    private companion object {
        /** EventLoop count: several concurrent carvers contending on one shared arena. */
        const val WORKERS = 8

        /**
         * 256 B → a small (subpage) size class. The freelist slot cap for classes
         * ≤ 512 B is 16 (`PooledAllocator.TINY_CLASS_SLOTS`), so [BATCH] above it
         * guarantees carve overflow.
         */
        const val SUBPAGE_CLASS_SIZE = 256

        /** Held per iteration; > the 16-slot subpage cap so the overflow carves. */
        const val BATCH = 64

        /** Iterations per worker — enough to drive sustained concurrent carving. */
        const val ITERATIONS = 5_000

        /** Per-worker join budget (wall-clock upper bound). */
        const val JOIN_BUDGET_MS = 30_000L
    }
}
