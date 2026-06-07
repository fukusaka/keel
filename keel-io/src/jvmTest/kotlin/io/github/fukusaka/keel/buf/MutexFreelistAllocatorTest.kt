package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `PoolAllocatorTest` contract repeated against a `PooledDirectAllocator`
 * configured with a `MutexFreelist` factory — confirms the `freelistFactory`
 * seam preserves every documented allocator behaviour when the strategy is
 * swapped from the default Treiber stack to the mutex variant.
 */
class MutexFreelistAllocatorTest {

    private fun allocator(maxPool: Int = 256): PooledDirectAllocator =
        PooledDirectAllocator(freelistFactory = ::MutexFreelist).also {
            // Match createPoolAllocator(bufferSize=8192, maxPoolSize=maxPool):
            // the default class is already registered at 8 KiB with DEFAULT_POOL_SLOTS;
            // re-registering with a different maxSlots is a no-op (duplicate-size guard),
            // so for the maxPool test we use a non-default size class instead.
            it.registerPoolSize(8192, maxPool)
        }

    @Test
    fun allocateReturnsBufferWithCorrectCapacity() {
        val a = allocator()
        val buf = a.allocate(8192)
        assertEquals(8192, buf.capacity)
        buf.release()
    }

    @Test
    fun releasedBufferIsReusedOnNextAllocate() {
        val a = allocator()
        val buf1 = a.allocate(8192)
        buf1.release()
        val buf2 = a.allocate(8192)
        assertSame(buf1, buf2)
        assertEquals(0, buf2.readerIndex)
        assertEquals(0, buf2.writerIndex)
        buf2.release()
    }

    @Test
    fun nonMatchingSizeFallsBackToFreshAllocation() {
        val a = allocator()
        val buf = a.allocate(1024)
        assertEquals(1024, buf.capacity)
        buf.release()
    }

    @Test
    fun poolDoesNotExceedMaxSize() {
        // Use a size class distinct from the default 8 KiB so registerPoolSize
        // can install our maxSlots without being shadowed by the init-time
        // 8 KiB / DEFAULT_POOL_SLOTS registration.
        val a = PooledDirectAllocator(freelistFactory = ::MutexFreelist)
        a.registerPoolSize(4096, 2)
        val bufs = (0 until 5).map { a.allocate(4096) }
        bufs.forEach { it.release() }

        val reused1 = a.allocate(4096)
        val reused2 = a.allocate(4096)
        val fresh = a.allocate(4096)
        assertTrue(bufs.contains(reused1))
        assertTrue(bufs.contains(reused2))
        assertEquals(4096, fresh.capacity)
        reused1.release()
        reused2.release()
        fresh.release()
    }

    @Test
    fun createForEventLoopReturnsNewInstance() {
        val a = allocator()
        val perEl = a.createForEventLoop()
        assertNotSame(a, perEl)
        // The child should inherit the same factory; allocating from the child
        // and releasing must still hit the pool reuse path.
        val buf1 = perEl.allocate(8192)
        buf1.release()
        val buf2 = perEl.allocate(8192)
        assertSame(buf1, buf2)
        buf2.release()
    }

    @Test
    fun trackingAllocatorSurvivesMultiCyclePoolReuse() {
        val tracker = TrackingAllocator(allocator())
        repeat(5) {
            val buf = tracker.allocate(8192)
            buf.release()
        }
        assertEquals(5, tracker.allocateCount)
        assertEquals(5, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
    }
}
