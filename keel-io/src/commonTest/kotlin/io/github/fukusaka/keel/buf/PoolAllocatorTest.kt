package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun roundsUpToASizeClassAndPools() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        // 1024 is its own Netty size class, so it pools with exact capacity.
        val buf = allocator.allocate(1024)
        assertEquals(1024, buf.capacity)
        buf.release()
        val reused = allocator.allocate(1024)
        assertSame(buf, reused, "released buffer should be reused from its size class")
        reused.release()
    }

    @Test
    fun poolDoesNotExceedClassCap() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        // The page-tier classes (incl. the 8 KiB read class) cap at PAGE_CLASS_SLOTS.
        val cap = PooledAllocator.PAGE_CLASS_SLOTS
        val bufs = (0 until cap + 4).map { allocator.allocate(8192) }
        bufs.forEach { it.release() } // only `cap` are retained; the rest are freed

        // Re-allocating up to `cap` returns the retained (reference-identical) buffers...
        val reused = (0 until cap).map { allocator.allocate(8192) }
        reused.forEach { assertTrue(bufs.contains(it), "expected a pooled buffer") }
        // ...and the pool is now empty, so the next allocate is a brand-new buffer.
        val fresh = allocator.allocate(8192)
        assertFalse(bufs.contains(fresh), "pool retained more than its cap")
        (reused + fresh).forEach { it.release() }
    }

    @Test
    fun createChildReturnsNewInstance() {
        if (!isPoolAllocator()) return
        val base = createPoolAllocator()
        val perEventLoop = base.createChild()
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
 * Creates the platform-specific pool allocator with its default size-class ladder.
 * Native: [SlabAllocator], JVM: [PooledDirectAllocator], JS: [DefaultAllocator].
 */
expect fun createPoolAllocator(): BufferAllocator

/**
 * Creates a [PooledAllocator] wired with the given [PoolMissProfile]. The
 * concrete platform allocator returned is the same as [createPoolAllocator];
 * this overload exists so [PoolMissProfileTest] can verify the wiring without
 * the test file needing platform-specific imports.
 */
expect fun createPoolAllocatorWithProfile(profile: PoolMissProfile): BufferAllocator

/**
 * Creates a [PooledAllocator] wired with the given
 * [BufferAllocatorLifecycleListener]. Same concrete allocator as
 * [createPoolAllocator]; this overload exists so the common lifecycle test
 * can drive the listener wiring without per-target imports.
 */
expect fun createPoolAllocatorWithListener(listener: BufferAllocatorLifecycleListener): BufferAllocator

/** Returns true if the platform has a real pool allocator (Native/JVM). */
expect fun isPoolAllocator(): Boolean
