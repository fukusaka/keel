@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.ReadinessIoTransport
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam tests for [ReadinessIoTransport]'s flush paths — the single-buffer
 * `write()` and the multi-buffer `writev()` gather — across their errno
 * and partial-write branches.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueTransportFlushSeamTest : KqueueTransportSeamFixture() {

    // --- flush / flushSingle ---

    @Test
    fun `flushSingle with Written completes in one call`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(5))
        }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush(), "flush must report the queue drained")
        assertEquals(1, fake.writeCalls)
        assertTrue(transport.flush(), "second flush is a no-op")
        assertEquals(1, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with partial Written loops until complete`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(
                fd,
                WriteResult.Written(3),
                WriteResult.Written(2),
            )
        }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush(), "flush must report the queue drained")
        assertEquals(2, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with WouldBlock defers the remainder at the head`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(
                fd,
                WriteResult.Written(3),
                WriteResult.WouldBlock,
                WriteResult.Written(2),
            )
        }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertFalse(transport.flush(), "WouldBlock must yield false (flush incomplete)")
        assertEquals(2, fake.writeCalls)
        assertTrue(transport.flush(), "remainder flushes cleanly")
        assertEquals(3, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with Failed drops buffer and returns true`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Failed(ECONNRESET))
        }
        // Allocated from the tracker, so the drop this test is named for has a
        // witness. The engine releases it; nothing here would notice otherwise.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

        val buf = tracker.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush(), "a dropped buffer still leaves nothing to flush")
        assertEquals(1, fake.writeCalls)
        assertTrue(transport.flush(), "second flush is a no-op")
        assertEquals(1, fake.writeCalls)
        fake.assertAllConsumed()
        tracker.assertNoLeaks()
    }

    @Test
    fun `flush with no pending writes returns true without syscall`() {
        val fake = FakeNativeSocket()
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)

        assertTrue(transport.flush(), "flush with nothing pending is a no-op")
        assertEquals(0, fake.writeCalls)
        assertEquals(0, fake.writevCalls)
    }

    // --- flush / flushGather (writev) ---

    @Test
    fun `flushGather with Written matching totalBytes completes`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Written(7))
        }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertTrue(transport.flush(), "flush must report the queue drained")
        assertEquals(1, fake.writevCalls)
        assertEquals(0, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with partial Written re-enqueues tail`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Written(4))
            enqueueWrite(fd, WriteResult.Written(6))
        }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 7 }
        transport.write(buf1)
        transport.write(buf2)

        assertFalse(transport.flush(), "partial write yields false")
        assertEquals(1, fake.writevCalls)

        assertTrue(transport.flush(), "flush must report the queue drained")
        assertEquals(1, fake.writeCalls, "remainder flushed via single-buffer path")
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with WouldBlock defers entire batch`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(
                fd,
                WriteResult.WouldBlock,
                WriteResult.Written(7),
            )
        }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertFalse(transport.flush(), "WouldBlock yields false")
        assertTrue(transport.flush(), "retry flushes the full batch")
        assertEquals(2, fake.writevCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with Failed drops all buffers`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Failed(EPIPE))
        }
        // Both from the tracker: "drops all buffers" means both are released,
        // and an emptied deque alone cannot tell a release from a discard.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)

        val buf1 = tracker.allocate(16).also { it.writerIndex = 3 }
        val buf2 = tracker.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertTrue(transport.flush(), "flush must report the queue drained")
        assertEquals(1, fake.writevCalls)
        assertTrue(transport.flush(), "second flush is a no-op")
        assertEquals(1, fake.writevCalls)
        fake.assertAllConsumed()
        tracker.assertNoLeaks()
    }
}
