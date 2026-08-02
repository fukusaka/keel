package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level tests for [CrossThreadReleaseProfile]: per-class bucketing, the
 * same-thread (non-cross-thread) accounting, the close()-bypass drop guard, and
 * the huge-slot fallthrough.
 *
 * These drive the listener **directly** (`onAllocated` / `onReleased`) rather
 * than through an allocate / release round-trip, because not every platform's
 * `defaultAllocator` fires the lifecycle listener (JS is unpooled and ignores
 * it). The allocate-then-release wiring — where `release()` fires `onReleased`
 * for real — and the cross-thread branch are covered in the jvmTest companion on
 * the pooled JVM allocator. All cases here run on a single thread, so every
 * release is same-thread; no wall-clock timeout is required.
 */
class CrossThreadReleaseProfileTest {

    @Test
    fun `same-thread release records a non-cross-thread release`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val buf = defaultAllocator().allocate(8192)
        profile.onAllocated(buf)
        profile.onReleased(buf) // same thread → alloc tid == free tid
        assertEquals(1L, profile.totalReleasesSnapshot().sum(), "one release recorded")
        assertEquals(0L, profile.crossThreadReleasesSnapshot().sum(), "same thread is not cross-thread")
        assertEquals(0L, profile.droppedReleases(), "the buffer had a recorded alloc thread")
        buf.release()
    }

    @Test
    fun `release with no recorded allocation is dropped not mis-attributed`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val buf = defaultAllocator().allocate(8192)
        // close()-bypass shape: onReleased with no prior onAllocated.
        profile.onReleased(buf)
        assertEquals(1L, profile.droppedReleases(), "release with no alloc entry is dropped")
        assertEquals(0L, profile.totalReleasesSnapshot().sum(), "dropped releases are not bucketed")
        buf.release()
    }

    @Test
    fun `releases bucket into distinct size classes`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val alloc = defaultAllocator()
        val small = alloc.allocate(8192)
        val large = alloc.allocate(16384)
        profile.onAllocated(small)
        profile.onReleased(small)
        profile.onAllocated(large)
        profile.onReleased(large)
        val totals = profile.totalReleasesSnapshot()
        assertEquals(2L, totals.sum(), "two releases total")
        assertEquals(2, totals.count { it == 1L }, "in two distinct size-class slots")
        small.release()
        large.release()
    }

    @Test
    fun `huge buffer buckets into the final slot`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        // > CHUNK_SIZE (256 KiB): size2SizeIdx returns the nSizes sentinel = the final slot.
        val huge = defaultAllocator().allocate(300_000)
        profile.onAllocated(huge)
        profile.onReleased(huge)
        val totals = profile.totalReleasesSnapshot()
        assertEquals(1L, totals.last(), "huge release lands in the final (huge) slot")
        assertTrue(totals.dropLast(1).all { it == 0L }, "no pooled-class slot was touched")
        huge.release()
    }

    @Test
    fun `reset clears the counters`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val buf = defaultAllocator().allocate(8192)
        profile.onAllocated(buf)
        profile.onReleased(buf)
        profile.reset()
        assertEquals(0L, profile.totalReleasesSnapshot().sum())
        assertEquals(0L, profile.crossThreadReleasesSnapshot().sum())
        assertEquals(0L, profile.droppedReleases())
        buf.release()
    }
}
