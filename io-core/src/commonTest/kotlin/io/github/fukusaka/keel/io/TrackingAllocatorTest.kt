package io.github.fukusaka.keel.io

import kotlin.test.Test
import kotlin.test.assertEquals

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

        // buf.release() triggers deallocator which increments releaseCount
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
        assertEquals(1, tracker.allocateCount)
        assertEquals(1, tracker.releaseCount)

        tracker.reset()
        assertEquals(0, tracker.allocateCount)
        assertEquals(0, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun delegatesToUnderlyingAllocator() {
        val tracker = TrackingAllocator(HeapAllocator)
        val buf = tracker.allocate(256)
        assertEquals(256, buf.capacity)
        buf.release()
    }
}
