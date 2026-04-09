package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackingAllocatorTest {

    @Test
    fun countsAllocateAndRelease() {
        val tracker = TrackingAllocator()
        assertEquals(0, tracker.allocateCount)
        assertEquals(0, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)

        val buf1 = tracker.allocate(64)
        val buf2 = tracker.allocate(128)
        assertEquals(2, tracker.allocateCount)
        assertEquals(0, tracker.releaseCount)
        assertEquals(2, tracker.outstandingCount)

        buf1.release()
        assertEquals(2, tracker.allocateCount)
        assertEquals(1, tracker.releaseCount)
        assertEquals(1, tracker.outstandingCount)

        buf2.release()
        assertEquals(2, tracker.allocateCount)
        assertEquals(2, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun resetClearsCounters() {
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(64)
        buf.release()
        tracker.reset()
        assertEquals(0, tracker.allocateCount)
        assertEquals(0, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun delegatesToUnderlyingAllocator() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val buf = tracker.allocate(256)
        assertEquals(256, buf.capacity)
        buf.release()
    }

    @Test
    fun assertNoLeaksSucceedsWhenBalanced() {
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(64)
        buf.release()
        tracker.assertNoLeaks()
    }

    @Test
    fun assertNoLeaksThrowsOnLeak() {
        val tracker = TrackingAllocator()
        tracker.allocate(64) // intentionally not released
        val ex = assertFailsWith<IllegalStateException> {
            tracker.assertNoLeaks()
        }
        assertTrue(ex.message!!.contains("outstanding=1"))
    }

    @Test
    fun assertNoLeaksCustomMessage() {
        val tracker = TrackingAllocator()
        tracker.allocate(64)
        val ex = assertFailsWith<IllegalStateException> {
            tracker.assertNoLeaks("custom leak message")
        }
        assertTrue(ex.message!!.contains("custom leak message"))
    }

    @Test
    fun createForEventLoopWrapsDelegate() {
        val tracker = TrackingAllocator()
        val perLoop = tracker.createForEventLoop()
        assertTrue(perLoop is TrackingAllocator)

        val buf = perLoop.allocate(64)
        buf.release()
        (perLoop as TrackingAllocator).assertNoLeaks()
    }

    @Test
    fun multipleBuffersMixedReleaseOrder() {
        val tracker = TrackingAllocator()
        val buf1 = tracker.allocate(64)
        val buf2 = tracker.allocate(128)
        val buf3 = tracker.allocate(256)
        assertEquals(3, tracker.outstandingCount)

        buf3.release()
        buf1.release()
        buf2.release()
        assertEquals(0, tracker.outstandingCount)
        tracker.assertNoLeaks()
    }

    @Test
    fun retainAndReleaseCountsCorrectly() {
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(64)
        buf.retain() // refCount = 2
        buf.release() // refCount = 1 — deallocator NOT called yet
        assertEquals(0, tracker.releaseCount, "Release with refCount > 0 should not trigger deallocator")
        assertEquals(1, tracker.outstandingCount)

        buf.release() // refCount = 0 — deallocator called
        assertEquals(1, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
    }
}
