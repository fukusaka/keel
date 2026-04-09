package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BufferAllocatorTest {

    @Test
    fun heapAllocatorRoundTrip() {
        val buf = DefaultAllocator.allocate(4)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(0x41.toByte(), buf.readByte())
        assertEquals(0x42.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun heapAllocatorCapacity() {
        val buf = DefaultAllocator.allocate(256)
        assertEquals(256, buf.capacity)
        buf.release()
    }

    @Test
    fun heapAllocatorReleaseDelegatesToRefCount() {
        val buf = DefaultAllocator.allocate(4)
        buf.retain() // refCount = 2
        buf.release() // refCount = 1, not freed
        // buf is still usable
        buf.writeByte(0x01)
        assertEquals(0x01.toByte(), buf.readByte())
        buf.release() // refCount = 0, freed
    }

    @Test
    fun heapAllocatorDoubleReleaseThrows() {
        val buf = DefaultAllocator.allocate(4)
        buf.release()
        assertFailsWith<IllegalStateException> {
            buf.release()
        }
    }

    @Test
    fun createForEventLoopReturnsSelf() {
        val allocator = DefaultAllocator.createForEventLoop()
        assertEquals(DefaultAllocator, allocator)
    }

    @Test
    fun `withTracking returns TrackingAllocator wrapping delegate`() {
        val tracker = DefaultAllocator.withTracking()
        assertTrue(tracker is TrackingAllocator)

        val buf = tracker.allocate(64)
        buf.release()
        tracker.assertNoLeaks()
    }

    @Test
    fun `withLeakDetection returns LeakDetectingAllocator wrapping delegate`() {
        val leaks = mutableListOf<String>()
        val allocator = DefaultAllocator.withLeakDetection { leaks.add(it) }
        assertTrue(allocator is LeakDetectingAllocator)

        val buf = allocator.allocate(64)
        buf.release()
        assertEquals(0, leaks.size)
    }

    @Test
    fun `chained withLeakDetection then withTracking`() {
        val leaks = mutableListOf<String>()
        val tracker = DefaultAllocator
            .withLeakDetection { leaks.add(it) }
            .withTracking()

        val buf = tracker.allocate(64)
        buf.release()

        assertEquals(0, leaks.size)
        tracker.assertNoLeaks()
    }
}
