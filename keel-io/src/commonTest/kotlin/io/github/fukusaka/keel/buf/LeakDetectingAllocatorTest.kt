package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeakDetectingAllocatorTest {

    @Test
    fun `released buffer does not trigger leak callback`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf = allocator.allocate(64)
        buf.release()

        assertEquals(0, leaks.size, "Released buffer should not trigger leak")
    }

    @Test
    fun `deallocator chain is preserved through leak detection`() {
        var deallocatorCalled = false
        val delegate = object : BufferAllocator {
            override fun allocate(capacity: Int): IoBuf {
                val buf = DefaultAllocator.allocate(capacity)
                (buf as PoolableIoBuf).deallocator = { deallocatorCalled = true }
                return buf
            }
        }
        val allocator = LeakDetectingAllocator(delegate) { }

        val buf = allocator.allocate(64)
        buf.release()

        assertTrue(deallocatorCalled, "Original deallocator should be called through leak detection")
    }

    @Test
    fun `createForEventLoop wraps delegate`() {
        val allocator = LeakDetectingAllocator(DefaultAllocator) { }
        val perLoop = allocator.createForEventLoop()

        assertTrue(perLoop is LeakDetectingAllocator, "createForEventLoop should return LeakDetectingAllocator")
    }

    @Test
    fun `retain and release lifecycle does not trigger leak`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf = allocator.allocate(64)
        buf.retain()
        buf.release() // refCount 1
        buf.release() // refCount 0 → deallocator

        assertEquals(0, leaks.size, "Properly released buffer should not trigger leak")
    }

    // GC-based leak detection tests are platform-specific:
    // - Native: kotlin.native.internal.GC.collect() triggers Cleaner
    // - JVM: System.gc() + drainLeakQueue on next allocation
    // - JS: no-op (GC-managed, no leak concern)
    //
    // These tests verify the deallocator interception mechanism.
    // Full GC-based verification is in nativeTest and jvmTest.
}
