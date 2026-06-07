package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    private fun allocator(): PooledDirectAllocator = PooledDirectAllocator(freelistFactory = ::MutexFreelist)

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
