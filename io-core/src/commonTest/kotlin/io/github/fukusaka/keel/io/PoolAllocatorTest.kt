package io.github.fukusaka.keel.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for pool-based allocator behavior shared by [SlabAllocator] (Native)
 * and [PooledDirectAllocator] (JVM).
 *
 * Uses the platform-specific pool allocator as the subject under test.
 * JS falls back to [HeapAllocator] (no pooling), so pool-specific tests
 * (reuse, maxPoolSize) are guarded by [isPoolAllocator].
 */
class PoolAllocatorTest {

    @Test
    fun allocateReturnsBufferWithCorrectCapacity() {
        val allocator = createPoolAllocator()
        val buf = allocator.allocate(8192)
        assertEquals(8192, buf.capacity)
        buf.release()
    }

    @Test
    fun releasedBufferIsReusedOnNextAllocate() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        val buf1 = allocator.allocate(8192)
        buf1.release()

        val buf2 = allocator.allocate(8192)
        assertSame(buf1, buf2)
        assertEquals(0, buf2.readerIndex)
        assertEquals(0, buf2.writerIndex)
        buf2.release()
    }

    @Test
    fun nonMatchingSizeFallsBackToFreshAllocation() {
        val allocator = createPoolAllocator()
        val buf = allocator.allocate(1024) // not 8192
        assertEquals(1024, buf.capacity)
        buf.release()
    }

    @Test
    fun poolDoesNotExceedMaxSize() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator(maxPoolSize = 2)
        val bufs = (0 until 5).map { allocator.allocate(8192) }
        bufs.forEach { it.release() }

        val reused1 = allocator.allocate(8192)
        val reused2 = allocator.allocate(8192)
        val fresh = allocator.allocate(8192)
        assertTrue(bufs.contains(reused1))
        assertTrue(bufs.contains(reused2))
        assertEquals(8192, fresh.capacity)
        reused1.release()
        reused2.release()
        fresh.release()
    }

    @Test
    fun createForEventLoopReturnsNewInstance() {
        if (!isPoolAllocator()) return
        val base = createPoolAllocator()
        val perEventLoop = base.createForEventLoop()
        assertNotSame(base, perEventLoop)
    }

    @Test
    fun trackingAllocatorWorksWithPoolAllocator() {
        val pool = createPoolAllocator()
        val tracker = TrackingAllocator(pool)
        val buf = tracker.allocate(8192)
        assertEquals(1, tracker.allocateCount)
        assertEquals(0, tracker.releaseCount)

        buf.release()
        assertEquals(1, tracker.allocateCount)
        assertEquals(1, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
    }
}

/**
 * Creates the platform-specific pool allocator.
 * Native: [SlabAllocator], JVM: [PooledDirectAllocator], JS: [HeapAllocator].
 */
expect fun createPoolAllocator(
    bufferSize: Int = 8192,
    maxPoolSize: Int = 256,
): BufferAllocator

/** Returns true if the platform has a real pool allocator (Native/JVM). */
expect fun isPoolAllocator(): Boolean
