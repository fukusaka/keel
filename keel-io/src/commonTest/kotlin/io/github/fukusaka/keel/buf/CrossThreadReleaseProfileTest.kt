package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level tests for [CrossThreadReleaseProfile]: the per-class bucketing,
 * the same-thread (non-cross-thread) accounting, the close()-bypass drop guard,
 * and the huge-slot fallthrough. All cases here run on a single thread, so every
 * release is same-thread; the cross-thread path (alloc thread ≠ free thread) is
 * exercised per-platform where a worker thread is available (see the jvmTest
 * companion).
 *
 * These are synchronous (allocate / release on the test thread, no dispatch or
 * I/O), so no wall-clock timeout is required.
 */
class CrossThreadReleaseProfileTest {

    @Test
    fun `same-thread allocate then release records a non-cross-thread release`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val alloc = defaultAllocator(NoOpStatsCounter, profile)
        alloc.allocate(8192).release()
        assertEquals(1L, profile.totalReleasesSnapshot().sum(), "one release recorded")
        assertEquals(0L, profile.crossThreadReleasesSnapshot().sum(), "same thread is not cross-thread")
        assertEquals(0L, profile.droppedReleases(), "the buffer had a recorded alloc thread")
    }

    @Test
    fun `release with no recorded allocation is dropped not mis-attributed`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val buf = defaultAllocator().allocate(8192)
        // Simulate a close()-bypass teardown: onReleased fires with no prior onAllocated.
        profile.onReleased(buf)
        assertEquals(1L, profile.droppedReleases(), "release with no alloc entry is dropped")
        assertEquals(0L, profile.totalReleasesSnapshot().sum(), "dropped releases are not bucketed")
        buf.release()
    }

    @Test
    fun `releases bucket into distinct size classes`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val alloc = defaultAllocator(NoOpStatsCounter, profile)
        alloc.allocate(8192).release()
        alloc.allocate(16384).release()
        val totals = profile.totalReleasesSnapshot()
        assertEquals(2L, totals.sum(), "two releases total")
        assertEquals(2, totals.count { it == 1L }, "in two distinct size-class slots")
    }

    @Test
    fun `huge buffer buckets into the final slot`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val alloc = defaultAllocator(NoOpStatsCounter, profile)
        // > CHUNK_SIZE (256 KiB): size2SizeIdx returns the nSizes sentinel = the final slot.
        alloc.allocate(300_000).release()
        val totals = profile.totalReleasesSnapshot()
        assertEquals(1L, totals.last(), "huge release lands in the final (huge) slot")
        assertTrue(totals.dropLast(1).all { it == 0L }, "no pooled-class slot was touched")
    }

    @Test
    fun `reset clears the counters`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val alloc = defaultAllocator(NoOpStatsCounter, profile)
        alloc.allocate(8192).release()
        profile.reset()
        assertEquals(0L, profile.totalReleasesSnapshot().sum())
        assertEquals(0L, profile.crossThreadReleasesSnapshot().sum())
        assertEquals(0L, profile.droppedReleases())
    }
}
