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

    // --- slice ---

    @Test
    fun `DefaultAllocator slice is a zero-copy view sharing the source backing`() {
        val src = DefaultAllocator.allocate(16)
        src.writeByteArray(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 0, 8)

        val slice = DefaultAllocator.slice(src, 2, 4) // bytes at index 2..5 -> 3,4,5,6
        assertEquals(4, slice.capacity)
        assertEquals(4, slice.readableBytes)
        assertEquals(3.toByte(), slice.getByte(0))
        assertEquals(6.toByte(), slice.getByte(3))

        // Zero-copy: overwriting the source region shows through the slice.
        src.clear()
        src.writeByteArray(byteArrayOf(0, 0, 99), 0, 3) // index 2 = slice[0]
        assertEquals(99.toByte(), slice.getByte(0))

        slice.release()
        src.release()
    }

    @Test
    fun `DefaultAllocator slice retains the source until the slice is released`() {
        val src = DefaultAllocator.allocate(8)
        src.writeByteArray(byteArrayOf(10, 20, 30, 40), 0, 4)
        val slice = DefaultAllocator.slice(src, 0, 4)

        // slice retained src (refCount 2); releasing src once leaves it alive.
        assertEquals(false, src.release())
        assertEquals(10.toByte(), slice.getByte(0))

        // Releasing the slice drops the retained reference, freeing src.
        assertTrue(slice.release())
    }

    @Test
    fun `DefaultAllocator slice of zero length is EmptyIoBuf`() {
        val src = DefaultAllocator.allocate(8)
        val slice = DefaultAllocator.slice(src, 0, 0)
        assertEquals(EmptyIoBuf, slice)
        src.release()
    }
}
