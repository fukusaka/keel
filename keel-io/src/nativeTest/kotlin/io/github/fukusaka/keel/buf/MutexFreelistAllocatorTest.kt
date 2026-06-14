package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * `PoolAllocatorTest` contract repeated against a `SlabAllocator` configured
 * with a `MutexFreelist` factory — confirms the `freelistFactory` seam
 * preserves every documented allocator behaviour when the strategy is swapped
 * from the default spin lock to the mutex variant.
 */
class MutexFreelistAllocatorTest {

    private fun allocator(): SlabAllocator = SlabAllocator(freelistFactory = ::MutexFreelist)

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
    fun roundsUpToASizeClassAndPools() {
        val a = allocator()
        val buf = a.allocate(1024) // 1024 is its own size class
        assertEquals(1024, buf.capacity)
        buf.release()
        assertSame(buf, a.allocate(1024).also { it.release() }, "released buffer is reused")
    }

    @Test
    fun poolDoesNotExceedClassCap() {
        val a = allocator()
        val cap = PooledAllocator.PAGE_CLASS_SLOTS
        val bufs = (0 until cap + 4).map { a.allocate(8192) }
        bufs.forEach { it.release() } // only `cap` retained; rest freed

        val reused = (0 until cap).map { a.allocate(8192) }
        reused.forEach { assertTrue(bufs.contains(it), "expected a pooled buffer") }
        val fresh = a.allocate(8192)
        assertFalse(bufs.contains(fresh), "pool retained more than its cap")
        (reused + fresh).forEach { it.release() }
    }

    @Test
    fun createForEventLoopReturnsNewInstance() {
        val a = allocator()
        val perEl = a.createForEventLoop()
        assertNotSame(a, perEl)
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

    @Test
    fun closeDestroysEveryMutexFreelistIdempotently() {
        val a = allocator()
        // Touch a few size classes so their MutexFreelist instances are
        // actually exercised before close. Without a pop/push the freelist
        // is still constructed (its mutex is initialised); close must
        // destroy each one cleanly either way.
        val small = a.allocate(64)
        val page = a.allocate(8192)
        val large = a.allocate(16 * 1024)
        small.release()
        page.release()
        large.release()

        // `pthread_mutex_destroy` on an initialised mutex with no waiters
        // returns 0. If the closed-flag guard in MutexFreelist is wrong
        // and the second close re-runs destroy on the freed slot, the
        // POSIX call returns EINVAL and `check()` throws — so the second
        // close passes here iff every freelist tracks its own closed flag.
        a.close()
        a.close()
    }
}
