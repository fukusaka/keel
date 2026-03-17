package io.github.keel.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
