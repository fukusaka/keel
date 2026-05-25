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

    /**
     * Decorator-nesting regression test: a [TrackingAllocator] wrapping
     * a pool allocator must not accumulate nested decorators across
     * pool recycles. The pool allocator resets the owner to the plain
     * pool owner on pop (PR #613 invariant), so a fresh `TrackingOwner`
     * wraps exactly once per cycle.
     *
     * Without that fix this test would trip the inner check on cycle 2
     * (release fires twice, but allocateCount is only 2 — the second
     * release++ makes releaseCount=3 > allocateCount=2).
     */
    @Test
    fun trackingAllocatorSurvivesMultiCyclePoolReuse() {
        if (!isPoolAllocator()) return
        val pool = createPoolAllocator()
        val tracker = TrackingAllocator(pool)
        repeat(5) {
            val buf = tracker.allocate(8192)
            buf.release()
        }
        assertEquals(5, tracker.allocateCount)
        assertEquals(5, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
    }

    /**
     * Same decorator-nesting invariant for [LeakDetectingAllocator].
     * Without owner reset on pop, repeated wrap would cause the inner
     * detectors to never see `released = true`.
     */
    @Test
    fun leakDetectingAllocatorSurvivesMultiCyclePoolReuse() {
        if (!isPoolAllocator()) return
        val leaks = mutableListOf<String>()
        val detected = LeakDetectingAllocator(createPoolAllocator()) { leaks.add(it) }
        repeat(5) {
            val buf = detected.allocate(8192)
            buf.release()
        }
        assertEquals(0, leaks.size, "released buffers must not be reported as leaks even with pool reuse")
    }

    /**
     * `LeakDetectingAllocator` + `TrackingAllocator` stack on a pool
     * allocator across multiple cycles. Exercises both decorators
     * being reset to the plain pool owner on pop.
     */
    @Test
    fun stackedDecoratorsSurviveMultiCyclePoolReuse() {
        if (!isPoolAllocator()) return
        val leaks = mutableListOf<String>()
        val tracker = TrackingAllocator(
            LeakDetectingAllocator(createPoolAllocator()) { leaks.add(it) },
        )
        repeat(5) {
            val buf = tracker.allocate(8192)
            buf.release()
        }
        assertEquals(5, tracker.allocateCount)
        assertEquals(5, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
        assertEquals(0, leaks.size)
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
