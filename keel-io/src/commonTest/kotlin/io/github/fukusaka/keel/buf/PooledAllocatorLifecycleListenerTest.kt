package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies that the lifecycle listener wiring on [PooledAllocator] fires
 * [BufferAllocatorLifecycleListener.onAllocated] on each allocate path and
 * [BufferAllocatorLifecycleListener.onReleased] on each release outcome.
 * Uses the per-platform `createPoolAllocatorWithListener` factory (declared
 * in `PoolAllocatorTest.{jvm,native}.kt`) so the common test exercises a
 * real `PooledAllocator`-backed allocator.
 *
 * The test skips on JS where the helper returns a stateless allocator that
 * does not drive lifecycle events (see [isPoolAllocator]).
 */
class PooledAllocatorLifecycleListenerTest {

    private class RecordingListener : BufferAllocatorLifecycleListener {
        val allocations = mutableListOf<IoBuf>()
        val releases = mutableListOf<IoBuf>()
        override fun onAllocated(buf: IoBuf) {
            allocations += buf
        }
        override fun onReleased(buf: IoBuf) {
            releases += buf
        }
    }

    @Test
    fun `lifecycle listener fires onAllocated on allocate and onReleased on release`() {
        if (!isPoolAllocator()) return
        val listener = RecordingListener()
        val allocator = createPoolAllocatorWithListener(listener)
        try {
            val buf = allocator.allocate(SizeTier.PAGE_MAX_BYTES)
            assertEquals(1, listener.allocations.size)
            assertSame(buf, listener.allocations[0])
            assertEquals(0, listener.releases.size)
            buf.release()
            assertEquals(1, listener.releases.size)
            assertSame(buf, listener.releases[0])
        } finally {
            (allocator as? AutoCloseableShim)?.close()
        }
    }

    @Test
    fun `lifecycle listener fires on the EMPTY allocate path`() {
        if (!isPoolAllocator()) return
        val listener = RecordingListener()
        val allocator = createPoolAllocatorWithListener(listener)
        try {
            val empty = allocator.allocate(0)
            assertEquals(1, listener.allocations.size)
            assertSame(empty, listener.allocations[0])
            empty.release()
            assertEquals(1, listener.releases.size)
        } finally {
            (allocator as? AutoCloseableShim)?.close()
        }
    }

    @Test
    fun `lifecycle listener fires on the HUGE allocate path`() {
        if (!isPoolAllocator()) return
        val listener = RecordingListener()
        val allocator = createPoolAllocatorWithListener(listener)
        try {
            val hugeSize = SizeTier.LARGE_MAX_BYTES + 1
            val buf = allocator.allocate(hugeSize)
            assertEquals(1, listener.allocations.size)
            buf.release()
            assertEquals(1, listener.releases.size)
        } finally {
            (allocator as? AutoCloseableShim)?.close()
        }
    }

    @Test
    fun `allocate-and-release across multiple buffers reports balanced counts`() {
        if (!isPoolAllocator()) return
        val listener = RecordingListener()
        val allocator = createPoolAllocatorWithListener(listener)
        try {
            val buffers = List(8) { allocator.allocate(SizeTier.PAGE_MAX_BYTES) }
            assertEquals(8, listener.allocations.size)
            buffers.forEach { it.release() }
            assertEquals(8, listener.releases.size)
            assertEquals(listener.allocations.size, listener.releases.size)
        } finally {
            (allocator as? AutoCloseableShim)?.close()
        }
    }

    @Test
    fun `TrackingAllocator installed as lifecycleListener counts via the listener path`() {
        if (!isPoolAllocator()) return
        val tracker = TrackingAllocator()
        val allocator = createPoolAllocatorWithListener(tracker)
        try {
            val a = allocator.allocate(SizeTier.PAGE_MAX_BYTES)
            val b = allocator.allocate(SizeTier.PAGE_MAX_BYTES)
            assertEquals(2, tracker.allocateCount)
            assertEquals(0, tracker.releaseCount)
            a.release()
            b.release()
            tracker.assertNoLeaks()
            assertTrue(tracker.outstandingCount == 0)
        } finally {
            (allocator as? AutoCloseableShim)?.close()
        }
    }
}

/**
 * Type-erased close shim — the per-target factory may or may not return a
 * closeable allocator. Keeps the test multiplatform without leaking
 * platform-specific types.
 */
private interface AutoCloseableShim {
    fun close()
}
