package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class IoBufWrapExternalTest {

    @Test
    fun `wrapExternal sets capacity and writerIndex`() {
        val ptr = nativeHeap.allocArray<ByteVar>(16)
        try {
            ptr[0] = 0x41
            ptr[1] = 0x42
            val buf = NativeIoBuf.wrapExternal(ptr, capacity = 16, bytesWritten = 2)
            assertEquals(16, buf.capacity)
            assertEquals(0, buf.readerIndex)
            assertEquals(2, buf.writerIndex)
            assertEquals(2, buf.readableBytes)
            assertEquals(14, buf.writableBytes)
            buf.close()
        } finally {
            nativeHeap.free(ptr.rawValue)
        }
    }

    @Test
    fun `wrapExternal reads data from external pointer`() {
        val ptr = nativeHeap.allocArray<ByteVar>(8)
        try {
            ptr[0] = 0x48 // 'H'
            ptr[1] = 0x69 // 'i'
            val buf = NativeIoBuf.wrapExternal(ptr, capacity = 8, bytesWritten = 2)
            assertEquals('H'.code.toByte(), buf.readByte())
            assertEquals('i'.code.toByte(), buf.readByte())
            buf.close()
        } finally {
            nativeHeap.free(ptr.rawValue)
        }
    }

    @Test
    fun `wrapExternal close does not free external memory`() {
        val ptr = nativeHeap.allocArray<ByteVar>(4)
        try {
            ptr[0] = 0x01
            val buf = NativeIoBuf.wrapExternal(ptr, capacity = 4, bytesWritten = 1)
            buf.close()
            // External memory is still accessible after close.
            assertEquals(0x01.toByte(), ptr[0])
        } finally {
            nativeHeap.free(ptr.rawValue)
        }
    }

    @Test
    fun `wrapExternal deallocator called on release`() {
        val ptr = nativeHeap.allocArray<ByteVar>(4)
        try {
            var deallocatorCalled = false
            val buf = NativeIoBuf.wrapExternal(
                ptr, capacity = 4, bytesWritten = 1,
                deallocator = { deallocatorCalled = true },
            )
            assertFalse(deallocatorCalled)
            buf.release()
            assertTrue(deallocatorCalled)
        } finally {
            nativeHeap.free(ptr.rawValue)
        }
    }

    @Test
    fun `wrapExternal retain and release lifecycle`() {
        val ptr = nativeHeap.allocArray<ByteVar>(4)
        try {
            var deallocatorCount = 0
            val buf = NativeIoBuf.wrapExternal(
                ptr, capacity = 4, bytesWritten = 0,
                deallocator = { deallocatorCount++ },
            )

            buf.retain()
            assertFalse(buf.release()) // refCount 2 → 1
            assertEquals(0, deallocatorCount)

            assertTrue(buf.release()) // refCount 1 → 0
            assertEquals(1, deallocatorCount)
        } finally {
            nativeHeap.free(ptr.rawValue)
        }
    }

    @Test
    fun `wrapExternal write and read`() {
        val ptr = nativeHeap.allocArray<ByteVar>(8)
        try {
            val buf = NativeIoBuf.wrapExternal(ptr, capacity = 8, bytesWritten = 0)
            buf.writeByte(0x61) // 'a'
            buf.writeByte(0x62) // 'b'
            assertEquals(2, buf.writerIndex)
            assertEquals('a'.code.toByte(), buf.readByte())
            assertEquals('b'.code.toByte(), buf.readByte())
            // Verify data is in external memory.
            assertEquals(0x61.toByte(), ptr[0])
            assertEquals(0x62.toByte(), ptr[1])
            buf.close()
        } finally {
            nativeHeap.free(ptr.rawValue)
        }
    }
}
