@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

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
internal class EpollTransportFlushSeamTest : EpollTransportSeamFixture() {

    // --- flush / flushSingle (single-buffer path) ---

    @Test
    fun `flushSingle with Written completes in one call`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(5))
        }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        val done = transport.flush()

        assertTrue(done, "flush must report the queue drained")
        assertEquals(1, fake.writeCalls)
        // A second flush() must be a no-op — pending queue is empty.
        assertTrue(transport.flush(), "second flush is a no-op")
        assertEquals(1, fake.writeCalls, "second flush must not call write()")
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with partial Written loops until complete`() {
        // Kernel often returns partial writes (SO_SNDBUF split) — the
        // engine must loop until all bytes are transferred.
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

        val done = transport.flush()

        assertTrue(done, "flush must report the queue drained")
        assertEquals(2, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with WouldBlock defers the remainder at the head`() {
        // Kernel-side send buffer full. Engine must:
        // - write what it could (3 bytes)
        // - re-enqueue the remaining 2 bytes with updated offset
        // - register an EPOLLOUT callback (exercised in integration test)
        // - return false (flush incomplete)
        // A second flush() attempt must resume from the remainder —
        // verifies the re-enqueue logic without needing access to the
        // protected `pendingBytes` field.
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

        // Second flush picks up the 2-byte remainder.
        assertTrue(transport.flush(), "remainder flushes cleanly")
        assertEquals(3, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with Failed drops buffer and returns true`() {
        // ECONNRESET / EPIPE: connection is unrecoverably broken. The
        // engine logs, releases the buffer, and returns `true` (flush
        // "done" — there's nothing left to send because the pipe is gone).
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

        val done = transport.flush()

        assertTrue(done, "a dropped buffer still leaves nothing to flush")
        assertEquals(1, fake.writeCalls)
        // A second flush() must not retry — the buffer was dropped.
        assertTrue(transport.flush(), "second flush is a no-op")
        assertEquals(1, fake.writeCalls)
        fake.assertAllConsumed()
        tracker.assertNoLeaks()
    }

    @Test
    fun `flush with no pending writes returns true without syscall`() {
        val fake = FakeNativeSocket()
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val done = transport.flush()

        assertTrue(done, "flush with nothing pending is a no-op")
        assertEquals(0, fake.writeCalls)
        assertEquals(0, fake.writevCalls)
    }

    // --- flush / flushGather (multi-buffer writev path) ---

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

        val done = transport.flush()

        assertTrue(done, "flush must report the queue drained")
        assertEquals(1, fake.writevCalls)
        assertEquals(0, fake.writeCalls, "writev path must not fall back to write()")
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with partial Written re-enqueues tail`() {
        // writev partial: 10 bytes requested, 4 written. First buffer
        // (3 bytes) fully consumed; second (7 bytes) has 1 byte written,
        // 6 bytes remaining with offset +1. Second flush picks up the
        // remainder via single-buffer path (flushSingle).
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

        // Second flush finds only the 6-byte remainder of buf2 (buf1
        // fully consumed in first writev). size == 1 → flushSingle path.
        assertTrue(transport.flush(), "flush must report the queue drained")
        assertEquals(1, fake.writeCalls, "remainder flushed via single-buffer path")
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with WouldBlock defers entire batch`() {
        // Nothing written → second flush must retry the full batch.
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

        val done = transport.flush()

        assertTrue(done, "flush must report the queue drained")
        assertEquals(1, fake.writevCalls)
        // Pending queue cleared — second flush must be a no-op.
        assertTrue(transport.flush(), "second flush is a no-op")
        assertEquals(1, fake.writevCalls)
        fake.assertAllConsumed()
        tracker.assertNoLeaks()
    }
}
