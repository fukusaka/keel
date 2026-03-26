package io.github.fukusaka.keel.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BufferAllocatorTest {

    @Test
    fun heapAllocatorRoundTrip() {
        val buf = HeapAllocator.allocate(4)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(0x41.toByte(), buf.readByte())
        assertEquals(0x42.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun heapAllocatorCapacity() {
        val buf = HeapAllocator.allocate(256)
        assertEquals(256, buf.capacity)
        buf.release()
    }

    @Test
    fun heapAllocatorReleaseDelegatesToRefCount() {
        val buf = HeapAllocator.allocate(4)
        buf.retain() // refCount = 2
        buf.release() // refCount = 1, not freed
        // buf is still usable
        buf.writeByte(0x01)
        assertEquals(0x01.toByte(), buf.readByte())
        buf.release() // refCount = 0, freed
    }

    @Test
    fun heapAllocatorDoubleReleaseThrows() {
        val buf = HeapAllocator.allocate(4)
        buf.release()
        assertFailsWith<IllegalStateException> {
            buf.release()
        }
    }

    @Test
    fun createForEventLoopReturnsSelf() {
        val allocator = HeapAllocator.createForEventLoop()
        assertEquals(HeapAllocator, allocator)
    }
}
