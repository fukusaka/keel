package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
    fun `owner chain is preserved through leak detection`() {
        var ownerReleaseCalled = false
        val customOwner = object : IoBufOwner {
            override fun release(buf: IoBuf) {
                ownerReleaseCalled = true
            }
        }
        val delegate = object : BufferAllocator {
            override fun allocate(capacity: Int): IoBuf {
                val buf = DefaultAllocator.allocate(capacity)
                (buf as PoolableIoBuf).owner = customOwner
                return buf
            }
            override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null
            override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
                DefaultAllocator.slice(source, offset, length)
        }
        val allocator = LeakDetectingAllocator(delegate) { }

        val buf = allocator.allocate(64)
        buf.release()

        assertTrue(ownerReleaseCalled, "Original owner release should be invoked through leak detection")
    }

    @Test
    fun `createChild wraps delegate`() {
        val allocator = LeakDetectingAllocator(DefaultAllocator) { }
        val perLoop = allocator.createChild()

        assertTrue(perLoop is LeakDetectingAllocator, "createChild should return LeakDetectingAllocator")
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

    @Test
    fun `zero capacity buffer lifecycle`() {
        val leaks = mutableListOf<String>()
        val allocator = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        val buf = allocator.allocate(0)
        assertEquals(0, buf.capacity)
        buf.release()

        assertEquals(0, leaks.size, "Zero-capacity buffer should not trigger leak")
    }

    @Test
    fun `decorator mode silently skips non-PoolableIoBuf delegate output`() {
        // Engine-direct buffers (NettyByteBufIoBuf etc.) do not implement
        // PoolableIoBuf. The decorator mode must silently pass them through
        // rather than ClassCastException — listener mode covers them instead.
        val leaks = mutableListOf<String>()
        val nonPoolableBuf = NonPoolableTestIoBuf()
        val delegate = object : BufferAllocator {
            override fun allocate(capacity: Int): IoBuf = nonPoolableBuf
            override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null
            override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
                error("not used in this test")
        }
        val allocator = LeakDetectingAllocator(delegate) { leaks.add(it) }

        val buf = allocator.allocate(64)

        assertSame(nonPoolableBuf, buf, "Non-PoolableIoBuf returned unchanged (decorator skipped)")
        assertEquals(0, leaks.size, "Skipped decoration does not fabricate a leak")
    }

    @Test
    fun `listener mode tracks allocation and release symmetrically`() {
        val leaks = mutableListOf<String>()
        val detector = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }
        val buf1 = NonPoolableTestIoBuf()
        val buf2 = NonPoolableTestIoBuf()

        detector.onAllocated(buf1)
        detector.onAllocated(buf2)
        assertEquals(2, detector.outstandingListenerCount)

        detector.onReleased(buf1)
        assertEquals(1, detector.outstandingListenerCount)

        detector.onReleased(buf2)
        assertEquals(0, detector.outstandingListenerCount)

        detector.reportOutstandingLeaks()
        assertEquals(0, leaks.size, "No leaks reported after balanced allocate/release")
    }

    @Test
    fun `listener mode reportOutstandingLeaks fires onLeak with allocation stack trace`() {
        val leaks = mutableListOf<String>()
        val detector = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }
        val leaked = NonPoolableTestIoBuf()

        detector.onAllocated(leaked)
        // onReleased deliberately not called.

        detector.reportOutstandingLeaks()

        assertEquals(1, leaks.size, "One outstanding allocation reported as leak")
        assertContains(leaks[0], "Unreleased buffer detected (listener mode)")
        assertContains(leaks[0], "Buffer allocated here")
    }

    @Test
    fun `listener mode reportOutstandingLeaks clears state so second call is a no-op`() {
        val leaks = mutableListOf<String>()
        val detector = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }

        detector.onAllocated(NonPoolableTestIoBuf())
        detector.reportOutstandingLeaks()
        assertEquals(1, leaks.size)

        detector.reportOutstandingLeaks()
        assertEquals(1, leaks.size, "Second report finds nothing outstanding")
        assertEquals(0, detector.outstandingListenerCount)
    }

    @Test
    fun `listener mode tracks identity not equality`() {
        // Even if two buffers had identical content, listener mode tracks by
        // object identity. Verify by registering then releasing only one
        // instance — the second instance must still be reported as leak.
        val leaks = mutableListOf<String>()
        val detector = LeakDetectingAllocator(DefaultAllocator) { leaks.add(it) }
        val buf1 = NonPoolableTestIoBuf()
        val buf2 = NonPoolableTestIoBuf()

        detector.onAllocated(buf1)
        detector.onAllocated(buf2)
        detector.onReleased(buf1)

        assertEquals(1, detector.outstandingListenerCount, "buf2 still tracked separately from buf1")
        detector.reportOutstandingLeaks()
        assertEquals(1, leaks.size)
    }

    // GC-based leak detection tests are platform-specific:
    // - Native: kotlin.native.internal.GC.collect() triggers Cleaner
    //   → nativeTest/LeakDetectingAllocatorGcTest.kt
    // - JVM: System.gc() + drainLeakQueue on next allocation
    //   → jvmTest/LeakDetectingAllocatorGcTest.kt
    // - JS: no-op (GC-managed, no leak concern)
    //
    // These tests verify the deallocator interception mechanism.
    // Full GC-based verification requires platform-specific test files.
}

/**
 * Minimal [IoBuf] that does NOT implement [PoolableIoBuf], emulating
 * engine-direct buffer types (`NettyByteBufIoBuf`, `RingBufferIoBuf`,
 * `DispatchDataIoBuf`) for decorator-skip and listener-mode tests.
 */
private class NonPoolableTestIoBuf : IoBuf {
    override val capacity: Int = 0
    override var readerIndex: Int = 0
    override var writerIndex: Int = 0
    override val readableBytes: Int = 0
    override val writableBytes: Int = 0
    override fun writeByte(value: Byte) = Unit
    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) = Unit
    override fun writeAscii(src: String, srcOffset: Int, length: Int) = Unit
    override fun copyTo(dest: IoBuf, length: Int) = Unit
    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) = Unit
    override fun readByte(): Byte = 0
    override fun getByte(index: Int): Byte = 0
    override fun clear() = Unit
    override fun retain(): IoBuf = this
    override fun release(): Boolean = true
    override fun close() = Unit
}
