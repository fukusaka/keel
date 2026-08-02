package io.github.fukusaka.keel.buf

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Reachability / regression test for concurrent allocate on the JVM
 * [PooledDirectAllocator]. A pooled channel consumed via `asSource` from a
 * non-EventLoop coroutine has the engine's push read path and the caller's pull
 * refill allocate on the same allocator concurrently (the JVM analogue of the
 * Native bug fixed in the per-call drain-scratch change). The JVM allocator does
 * no cross-thread routing, so both threads pop/push the same freelist directly.
 *
 * The default freelist is a lock-free intrusive `TreiberStackFreelist`, documented
 * ABA-unsafe and "safe only because each allocator is EventLoop-pinned". Under
 * genuine concurrent pop/push the ABA hazard lets a buffer be popped twice or lost,
 * surfacing as a double-free (`"already released"`) or a leak imbalance.
 */
class PooledDirectAllocatorConcurrencyTest {

    private companion object {
        // A Netty ladder size class that round-trips through the pool.
        private const val CLASS = 512
        private const val WORKERS = 6
        private const val ITERS = 100_000
    }

    @Test
    fun `concurrent allocate-release churn keeps the freelist consistent`() {
        val allocated = AtomicLong(0)
        val released = AtomicLong(0)
        val listener = object : BufferAllocatorLifecycleListener {
            override fun onAllocated(buf: IoBuf) {
                allocated.incrementAndGet()
            }

            override fun onReleased(buf: IoBuf) {
                released.incrementAndGet()
            }
        }
        val allocator = PooledDirectAllocator(lifecycleListener = listener)
        val failure = AtomicReference<Throwable?>(null)
        try {
            val threads = (0 until WORKERS).map {
                Thread {
                    try {
                        repeat(ITERS) { allocator.allocate(CLASS).release() }
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            assertNull(
                failure.get(),
                "concurrent allocate/release must not corrupt the freelist (ABA / double-free): ${failure.get()}",
            )
            assertEquals(
                allocated.get(),
                released.get(),
                "every allocated buffer must fire onReleased exactly once — an imbalance signals a lost/double buffer",
            )
        } finally {
            allocator.close()
        }
    }
}
