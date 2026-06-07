package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProfilingAllocatorTest {

    @Test
    fun `bucketOf maps sizes to ceil-log2 buckets`() {
        assertEquals(0, AllocationProfile.bucketOf(0))
        assertEquals(0, AllocationProfile.bucketOf(1))
        assertEquals(1, AllocationProfile.bucketOf(2))
        assertEquals(4, AllocationProfile.bucketOf(16))
        // exact power of two lands at its own log2 (bucket k = (2^(k-1), 2^k])
        assertEquals(13, AllocationProfile.bucketOf(8192))
        // one byte over a power of two rolls into the next bucket
        assertEquals(14, AllocationProfile.bucketOf(8193))
        // 100 KiB /large body
        assertEquals(17, AllocationProfile.bucketOf(100_000))
    }

    @Test
    fun `bucketUpperBound reports the inclusive upper bound`() {
        assertEquals(1L, AllocationProfile.bucketUpperBound(0))
        assertEquals(8192L, AllocationProfile.bucketUpperBound(13))
        assertEquals(16384L, AllocationProfile.bucketUpperBound(14))
    }

    @Test
    fun `record accumulates counts per bucket and total`() {
        val profile = AllocationProfile()
        repeat(3) { profile.record(8192) }
        profile.record(8193)
        profile.record(100_000)

        val snap = profile.snapshot()
        assertEquals(3L, snap[13])
        assertEquals(1L, snap[14])
        assertEquals(1L, snap[17])
        assertEquals(5L, profile.total())
    }

    @Test
    fun `reset clears all buckets`() {
        val profile = AllocationProfile()
        profile.record(8192)
        profile.record(64)
        assertEquals(2L, profile.total())

        profile.reset()
        assertEquals(0L, profile.total())
    }

    @Test
    fun `ProfilingAllocator records allocate sizes and delegates`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val profiling = tracker.withProfiling()

        val a = profiling.allocate(8192)
        val b = profiling.allocate(513)
        a.release()
        b.release()

        // sizes recorded into the histogram
        val snap = profiling.profile.snapshot()
        assertEquals(1L, snap[13], "8192 -> bucket 13")
        assertEquals(1L, snap[10], "513 -> bucket 10 (4,512] is 9; (512,1024] is 10)")
        assertEquals(2L, profiling.profile.total())

        // delegated through to the underlying allocator (allocate/release counted)
        assertEquals(2, tracker.allocateCount)
        assertEquals(2, tracker.releaseCount)
    }

    @Test
    fun `createForEventLoop shares the same profile across EventLoops`() {
        val parent = DefaultAllocator.withProfiling()
        val el1 = parent.createForEventLoop() as ProfilingAllocator
        val el2 = parent.createForEventLoop() as ProfilingAllocator

        // both children share the parent's profile instance
        assertTrue(el1.profile === parent.profile)
        assertTrue(el2.profile === parent.profile)

        el1.allocate(8192).release()
        el2.allocate(8192).release()

        // both EventLoops aggregate into one histogram
        assertEquals(2L, parent.profile.snapshot()[13])
        assertEquals(2L, parent.profile.total())
    }

    @Test
    fun `format renders a non-empty histogram`() {
        val profile = AllocationProfile()
        assertContains(profile.format(), "no allocations")

        repeat(4) { profile.record(8192) }
        val rendered = profile.format()
        assertContains(rendered, "total=4")
        assertContains(rendered, "8192")
    }
}
