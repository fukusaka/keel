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

    @Test
    fun `composable with TrackingAllocator - LeakDetecting outside`() {
        val leaks = mutableListOf<String>()
        val tracking = TrackingAllocator(DefaultAllocator)
        val allocator = LeakDetectingAllocator(tracking) { leaks.add(it) }

        val buf = allocator.allocate(64)
        buf.release()

        assertEquals(0, leaks.size)
        tracking.assertNoLeaks()
    }

    @Test
    fun `composable with TrackingAllocator - TrackingAllocator outside`() {
        val leaks = mutableListOf<String>()
        val inner = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }
        val tracking = TrackingAllocator(inner)

        val buf = tracking.allocate(64)
        buf.release()

        assertEquals(0, leaks.size)
        tracking.assertNoLeaks()
    }

    @Test
    fun `multiple buffers with mixed release order`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf1 = allocator.allocate(64)
        val buf2 = allocator.allocate(128)
        val buf3 = allocator.allocate(256)

        buf2.release()
        buf1.release()
        buf3.release()

        assertEquals(0, leaks.size)
    }

    // GC-based leak detection tests are platform-specific:
    // - Native: kotlin.native.internal.GC.collect() triggers Cleaner
    // - JVM: System.gc() + drainLeakQueue on next allocation
    // - JS: no-op (GC-managed, no leak concern)
    //
    // These tests verify the deallocator interception mechanism.
    // Full GC-based verification requires platform-specific test files.
}
