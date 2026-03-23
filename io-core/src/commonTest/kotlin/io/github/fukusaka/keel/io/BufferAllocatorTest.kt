package io.github.fukusaka.keel.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BufferAllocatorTest {

    @Test
    fun heapAllocatorRoundTrip() {
        val buf = HeapAllocator.allocate(4)
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(0x41.toByte(), buf.readByte())
        assertEquals(0x42.toByte(), buf.readByte())
        HeapAllocator.release(buf)
    }

    @Test
    fun heapAllocatorCapacity() {
        val buf = HeapAllocator.allocate(256)
        assertEquals(256, buf.capacity)
        HeapAllocator.release(buf)
    }

    @Test
    fun heapAllocatorReleaseDelegatesToRefCount() {
        val buf = HeapAllocator.allocate(4)
        buf.retain() // refCount = 2
        HeapAllocator.release(buf) // refCount = 1, not freed
        // buf is still usable
        buf.writeByte(0x01)
        assertEquals(0x01.toByte(), buf.readByte())
        HeapAllocator.release(buf) // refCount = 0, freed
    }

    @Test
    fun heapAllocatorDoubleReleaseThrows() {
        val buf = HeapAllocator.allocate(4)
        HeapAllocator.release(buf)
        assertFailsWith<IllegalStateException> {
            HeapAllocator.release(buf)
        }
    }
}
