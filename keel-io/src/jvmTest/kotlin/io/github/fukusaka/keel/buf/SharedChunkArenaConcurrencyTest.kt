@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cross-thread seam test for the shared [ChunkArena] — a thread-safe arena
 * shared by `createChild`. Exercises the off-EventLoop-safety the sharing targets:
 * a buffer carved on one thread (a producer simulating EventLoop A) is released on
 * another (a consumer simulating EventLoop B), so the shared arena's [carve] and
 * [returnRun] run concurrently under the [ArenaLock].
 *
 * The producers allocate the same pool size class repeatedly and hand each buffer
 * to a consumer that releases it. Because every release crosses to a different
 * thread, the allocating thread's freelist cache never refills, so almost every
 * allocate is a pool miss that hits `chunkArena.carve` — and every release returns
 * its run via `chunkArena.returnRun`. Without the [ArenaLock] this races the
 * `PoolChunk` subpage bookkeeping and surfaces as `IllegalStateException: no
 * subpage at run offset N` (the PR #815 crash class) or a corrupted pool.
 *
 * Pass criteria: no thread observes an exception, and every carved buffer is
 * released exactly once (`allocated == released`), with a clean teardown.
 *
 * JVM-only by design (uses `java.lang.Thread`); the Native `ArenaLock` actual is a
 * `pthread_mutex` sharing [MutexFreelist]'s proven lifecycle, and the arena logic
 * under test lives in commonMain, so this seam covers the shared-arena contract
 * for both platforms.
 */
class SharedChunkArenaConcurrencyTest {

    @Test
    fun `cross-thread carve and release on a shared arena stays consistent`() {
        val root = PooledDirectAllocator()
        // Per-EventLoop children that all share the root's chunk arena.
        val children = Array(CHILDREN) { root.createChild() }
        val errors = AtomicInteger(0)
        val firstError = AtomicReference<Throwable?>(null)
        val allocated = AtomicInteger(0)
        val released = AtomicInteger(0)
        // A capacity-0 sentinel signalling "no more work" through the handoff queue;
        // never released or counted (it carries no chunk run).
        val sentinel: IoBuf = DirectIoBuf(0)
        // Bounded so producers block when consumers fall behind, capping in-flight
        // buffers. An unbounded queue let fast producers pile up enough live carves
        // to exhaust the JVM's 512 MiB direct-buffer limit (a flaky OOM under a full
        // build that shares that limit across tests) — not an ArenaLock fault.
        val queue = LinkedBlockingQueue<IoBuf>(QUEUE_CAPACITY)
        val start = CountDownLatch(1)

        // Built outside the try so the finally can ask whether they have stopped.
        val producers = Array(PRODUCERS) { tid ->
            workerThread("producer-$tid") {
                try {
                    start.awaitWithin("producer start")
                    val child = children[tid % CHILDREN]
                    repeat(OPS_PER_PRODUCER) {
                        val buf = child.allocate(CLASS_SIZE)
                        buf.writeByte(0) // touch the carved region
                        queue.put(buf)
                        allocated.incrementAndGet()
                    }
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    firstError.compareAndSet(null, t)
                    errors.incrementAndGet()
                }
            }
        }
        val consumers = Array(CONSUMERS) { tid ->
            workerThread("consumer-$tid") {
                try {
                    while (true) {
                        val buf = queue.take()
                        if (buf === sentinel) break
                        buf.release() // cross-thread: returns the run to the shared arena
                        released.incrementAndGet()
                    }
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    firstError.compareAndSet(null, t)
                    errors.incrementAndGet()
                }
            }
        }

        try {
            producers.forEach { it.start() }
            consumers.forEach { it.start() }
            start.countDown()
            // Bounded joins: a deadlock in the shared ArenaLock (the failure class
            // under test) would otherwise block these joins forever. Cap the wait
            // and fail on any surviving thread instead of hanging the suite.
            producers.asList().joinAllWithin("producers (ArenaLock deadlock guard)")
            // All work enqueued: stop each consumer with one sentinel. Bounded, because
            // this runs on the test thread: if the consumers have died the queue stays
            // full and an unbounded put() hangs the test itself, ahead of every
            // assertion that would have named the real failure.
            queue.offerAllWithin(List(CONSUMERS) { sentinel }, "sentinel handoff")
            consumers.asList().joinAllWithin("consumers (ArenaLock deadlock guard)")

            assertEquals(
                0,
                errors.get(),
                "no producer/consumer observed an exception; first: ${firstError.get()?.stackTraceToString()}",
            )
            assertEquals(
                allocated.get(),
                released.get(),
                "every carved buffer was released exactly once (no leak, no double-free)",
            )
            assertEquals(PRODUCERS * OPS_PER_PRODUCER, allocated.get(), "all producers completed")
        } finally {
            // Only once every worker has stopped: PooledAllocator.close() documents a
            // single-threaded teardown, and the bounded joins reach this finally with a
            // worker possibly still carving from the arena under test.
            (producers.asList() + consumers.asList()).tearDownWhenStopped {
                children.forEach { it.close() }
                root.close()
            }
        }
    }

    private companion object {
        const val CHILDREN = 4
        const val PRODUCERS = 8
        const val CONSUMERS = 8
        const val OPS_PER_PRODUCER = 20_000

        // Backpressure bound on in-flight buffers (×CLASS_SIZE ≈ 2 MiB), keeping the
        // test well within the JVM direct-buffer limit regardless of producer speed.
        const val QUEUE_CAPACITY = 256

        // A pooled class size: a cache miss carves it from a chunk (the path the
        // shared arena guards). Kept well under the chunk size so it pools normally.
        const val CLASS_SIZE = 8192
    }
}
