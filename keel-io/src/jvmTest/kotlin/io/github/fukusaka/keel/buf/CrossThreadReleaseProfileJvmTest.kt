package io.github.fukusaka.keel.buf

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * JVM cross-thread test for [CrossThreadReleaseProfile]: allocate on the test
 * thread, drive the release from a worker thread, and assert the profile counts
 * it as cross-thread. This is the path the commonTest cannot reach (it has no
 * portable worker-thread primitive); the bucketing / drop logic it shares is
 * covered there, so this isolates only the alloc-thread ≠ free-thread branch.
 *
 * The worker [Thread.join] is bounded so a hung release cannot stall CI.
 */
class CrossThreadReleaseProfileJvmTest {

    @Test
    fun `release on a different thread is counted as cross-thread`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val alloc = defaultAllocator(NoOpStatsCounter, profile)
        val buf = alloc.allocate(8192) // onAllocated fires on the test thread
        val worker = thread(start = true) { buf.release() } // onReleased fires on the worker
        worker.join(JOIN_TIMEOUT_MS)
        assertEquals(1L, profile.totalReleasesSnapshot().sum(), "one release recorded")
        assertEquals(1L, profile.crossThreadReleasesSnapshot().sum(), "alloc thread ≠ free thread is cross-thread")
        assertEquals(0L, profile.droppedReleases(), "the buffer had a recorded alloc thread")
    }

    @Test
    fun `release back on the allocating thread is not cross-thread`() {
        val profile = CrossThreadReleaseProfile.forDefaultPool()
        val alloc = defaultAllocator(NoOpStatsCounter, profile)
        // Allocate and release both on a single worker thread → same thread id.
        val worker = thread(start = true) {
            alloc.allocate(8192).release()
        }
        worker.join(JOIN_TIMEOUT_MS)
        assertEquals(1L, profile.totalReleasesSnapshot().sum())
        assertEquals(0L, profile.crossThreadReleasesSnapshot().sum(), "same worker thread is not cross-thread")
    }

    private companion object {
        const val JOIN_TIMEOUT_MS = 5_000L
    }
}
