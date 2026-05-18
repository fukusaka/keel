package io.github.fukusaka.keel.buf

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
 * JS falls back to [DefaultAllocator] (no pooling), so pool-specific tests
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
    fun offSizeRequestRoundsUpToSizeClass() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        // 100 B is not a size class; it rounds up to 112 (octave 64: 64,80,96,112).
        val buf = allocator.allocate(100)
        assertEquals(112, buf.capacity)
        buf.release()
    }

    @Test
    fun offSizeRequestIsPooledAndReused() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        val buf1 = allocator.allocate(100) // rounds to 112
        buf1.release()
        // Next request rounding to the same class reuses the pooled buffer.
        val buf2 = allocator.allocate(110) // also rounds to 112
        assertSame(buf1, buf2)
        assertEquals(112, buf2.capacity)
        buf2.release()
    }

    @Test
    fun hugeRequestIsServedUnPooledAtExactSize() {
        val allocator = createPoolAllocator()
        // Above MAX_SIZE_CLASS (32768): un-pooled, exact capacity.
        val buf = allocator.allocate(40000)
        assertEquals(40000, buf.capacity)
        buf.release()
    }

    @Test
    fun budgetValveClosesBuffersPastMaxTotalBytes() {
        if (!isPoolAllocator()) return
        // 256 KiB budget. 32 KiB buffers => only 8 fit before the valve closes the rest.
        val allocator = createPoolAllocator()
        val bufs = (0 until 16).map { allocator.allocate(32768) }
        bufs.forEach { it.release() }
        // At most 8 buffers were retained; the rest were closed by the valve.
        var reusedCount = 0
        val reused = (0 until 16).map { allocator.allocate(32768) }
        reused.forEach { r ->
            if (bufs.contains(r)) reusedCount++
        }
        assertTrue(reusedCount <= 8, "budget valve must retain at most 8 of the 32 KiB buffers")
        reused.forEach { it.release() }
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
    fun anySizeClassRequestIsPooled() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        // 1024 is a size class distinct from the 8192 default; it is pooled.
        val buf1 = allocator.allocate(1024)
        assertEquals(1024, buf1.capacity)
        buf1.release()
        val buf2 = allocator.allocate(1024)
        assertSame(buf1, buf2)
        buf2.release()
    }

    @Test
    fun poolDoesNotExceedMaxSlots() {
        if (!isPoolAllocator()) return
        // The 4096 class keeps its default maxSlots of 16 (only the bufferSize
        // class is bumped by the helper). Release 20 buffers; 16 retained.
        val allocator = createPoolAllocator()
        val bufs = (0 until 20).map { allocator.allocate(4096) }
        bufs.forEach { it.release() }

        var reusedCount = 0
        val reused = (0 until 20).map { allocator.allocate(4096) }
        reused.forEach { r ->
            if (bufs.contains(r)) reusedCount++
        }
        assertEquals(16, reusedCount, "pool must retain exactly the default 16 buffers per class")
        reused.forEach { it.release() }
    }

    @Test
    fun registerPoolSizeBumpsMaxSlotsForCoveringClass() {
        if (!isPoolAllocator()) return
        // Bump the 4096 class to 24 slots via the bufferSize parameter.
        val allocator = createPoolAllocator(bufferSize = 4096, maxPoolSize = 24)
        val bufs = (0 until 24).map { allocator.allocate(4096) }
        bufs.forEach { it.release() }

        var reusedCount = 0
        val reused = (0 until 24).map { allocator.allocate(4096) }
        reused.forEach { r ->
            if (bufs.contains(r)) reusedCount++
        }
        assertEquals(24, reusedCount, "registerPoolSize must raise retained slots to 24")
        reused.forEach { it.release() }
    }

    @Test
    fun createForEventLoopReturnsNewInstance() {
        if (!isPoolAllocator()) return
        val base = createPoolAllocator()
        val perEventLoop = base.createForEventLoop()
        assertNotSame(base, perEventLoop)
    }

    @Test
    fun createForEventLoopClampsPerClassSlotsToTheLocalCap() {
        if (!isPoolAllocator()) return
        // The parent's 4096 class keeps the default 16 slots; the per-EventLoop
        // child must clamp every class to the local cap of 8.
        val child = createPoolAllocator().createForEventLoop()
        val bufs = (0 until 12).map { child.allocate(4096) }
        bufs.forEach { it.release() }

        var reusedCount = 0
        val reused = (0 until 12).map { child.allocate(4096) }
        reused.forEach { r -> if (bufs.contains(r)) reusedCount++ }
        assertEquals(8, reusedCount, "a per-EventLoop child pool must retain at most the local cap of 8")
        reused.forEach { it.release() }
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
 * Native: [SlabAllocator], JVM: [PooledDirectAllocator], JS: [DefaultAllocator].
 */
expect fun createPoolAllocator(
    bufferSize: Int = 8192,
    maxPoolSize: Int = 256,
): BufferAllocator

/** Returns true if the platform has a real pool allocator (Native/JVM). */
expect fun isPoolAllocator(): Boolean
