package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TrackingAllocatorTest {

    @Test
    fun `allocate and release updates counts correctly`() {
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
    fun `reset clears all counters`() {
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(64)
        buf.release()
        tracker.reset()
        assertEquals(0, tracker.allocateCount)
        assertEquals(0, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `delegates to underlying allocator with correct capacity`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val buf = tracker.allocate(256)
        assertEquals(256, buf.capacity)
        buf.release()
    }

    @Test
    fun `assertNoLeaks succeeds when all buffers released`() {
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(64)
        buf.release()
        tracker.assertNoLeaks()
    }

    @Test
    fun `assertNoLeaks throws when buffer not released`() {
        val tracker = TrackingAllocator()
        tracker.allocate(64)
        val ex = assertFailsWith<IllegalStateException> {
            tracker.assertNoLeaks()
        }
        assertTrue(ex.message!!.contains("outstanding=1"))
    }

    @Test
    fun `assertNoLeaks includes custom message`() {
        val tracker = TrackingAllocator()
        tracker.allocate(64)
        val ex = assertFailsWith<IllegalStateException> {
            tracker.assertNoLeaks("custom leak message")
        }
        assertTrue(ex.message!!.contains("custom leak message"))
    }

    @Test
    fun `createForEventLoop returns TrackingAllocator wrapping delegate`() {
        val tracker = TrackingAllocator()
        val perLoop = tracker.createForEventLoop()
        assertTrue(perLoop is TrackingAllocator)

        val buf = perLoop.allocate(64)
        buf.release()
        perLoop.assertNoLeaks()
    }

    @Test
    fun `multiple buffers with mixed release order`() {
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
    fun `retain and release counts deallocator only at refCount zero`() {
        val tracker = TrackingAllocator()
        val buf = tracker.allocate(64)
        buf.retain()
        buf.release()
        assertEquals(0, tracker.releaseCount, "Release with refCount > 0 should not trigger deallocator")
        assertEquals(1, tracker.outstandingCount)

        buf.release()
        assertEquals(1, tracker.releaseCount)
        assertEquals(0, tracker.outstandingCount)
    }
}
