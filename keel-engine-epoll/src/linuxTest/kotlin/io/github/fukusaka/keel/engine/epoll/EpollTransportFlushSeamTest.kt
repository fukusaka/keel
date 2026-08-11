package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam tests for [EpollIoTransport]'s flush paths — the single-buffer
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
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        val done = transport.flush()

        assertTrue(done, "flush() must return true when all bytes written")
        assertEquals(1, fake.writeCalls)
        // A second flush() must be a no-op — pending queue is empty.
        assertTrue(transport.flush())
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
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        val done = transport.flush()

        assertTrue(done)
        assertEquals(2, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with WouldBlock re-enqueues remainder`() {
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
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

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
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        val done = transport.flush()

        assertTrue(done, "Failed must yield true (nothing left to drain)")
        assertEquals(1, fake.writeCalls)
        // A second flush() must not retry — the buffer was dropped.
        assertTrue(transport.flush())
        assertEquals(1, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle whose write throws leaves the buffer where the teardown looks`() {
        // `performFlush` takes the entry off the deque before calling the
        // socket, so a write that throws is the one path where the buffer is
        // in nobody's hands: not queued for the teardown, and not the
        // caller's, since `write` took ownership when it was enqueued.
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { flushThrowsOnce = InjectedFault("write refused") }
        val transport = EpollIoTransport(fd, eventLoop, tracker, fake)

        val buf = tracker.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertFailsWith<InjectedFault> { transport.flush() }

        assertEquals(1, fake.writeCalls, "the write must have been attempted for this test to mean anything")
        assertEquals(1, tracker.outstandingCount, "the entry is still owed a release, not released early")

        // Stop the loop so the transport's teardown runs on this thread: these
        // seam tests never start one, and a teardown offered to a queue nothing
        // drains would answer neither way.
        eventLoop.close()
        assertStrandedWritesReleased(transport, tracker)
    }

    @Test
    fun `flushSingle whose write throws part way keeps only what is unsent`() {
        // The throw lands after 3 of the 5 bytes are gone. Re-queueing the
        // entry whole would put those 3 back on the wire on the next flush, so
        // what goes back is the remainder -- and the single scripted write
        // below is what says so: 2 bytes finish it, and a whole entry would
        // need another call.
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(3))
            flushThrowsOnce = InjectedFault("write refused after a partial send")
            flushThrowsAfterCalls = 1
        }
        val transport = EpollIoTransport(fd, eventLoop, tracker, fake)

        val buf = tracker.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertFailsWith<InjectedFault> { transport.flush() }
        assertEquals(2, fake.writeCalls, "the second write is the one that must have thrown")
        assertEquals(1, tracker.outstandingCount, "the entry is still owed a release, not released early")

        val callsBeforeRetry = fake.writeCalls
        fake.enqueueWrite(fd, WriteResult.Written(2))
        assertTrue(transport.flush(), "the retry flush completes")
        assertEquals(
            callsBeforeRetry + 1,
            fake.writeCalls,
            "the retry must send exactly the 2 unsent bytes: no extra call means nothing went back " +
                "at all, two means the whole entry went back and the first 3 bytes were sent twice",
        )
        assertEquals(0, tracker.outstandingCount, "the completed write releases its buffer")
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle whose failure log throws has already released the buffer`() {
        // The entry is out of the deque for the whole branch, not just for the
        // write, so anything that throws before the release strands it the same
        // way. `Logger` is a public SPI the engine takes through its config, so
        // the warn line is caller code: releasing after it put a third party
        // between the buffer and its only release.
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.Failed(ECONNRESET)) }
        val loop = EpollEventLoop(throwingWarnLogger(), flushCoalescing = false)
        try {
            val transport = EpollIoTransport(fd, loop, tracker, fake)
            val buf = tracker.allocate(16)
            buf.writerIndex = 5
            transport.write(buf)

            assertFailsWith<InjectedFault> { transport.flush() }

            assertEquals(0, tracker.outstandingCount, "the buffer must be released before the log is attempted")
        } finally {
            loop.close()
        }
    }

    /** Stands in for a caller-supplied logger that fails on the warn path. */
    private fun throwingWarnLogger(): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = true

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.WARN) throw InjectedFault("the logger refused")
        }
    }

    @Test
    fun `flush with no pending writes returns true without syscall`() {
        val fake = FakeNativeSocket()
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val done = transport.flush()

        assertTrue(done)
        assertEquals(0, fake.writeCalls)
        assertEquals(0, fake.writevCalls)
    }

    // --- flush / flushGather (multi-buffer writev path) ---

    @Test
    fun `flushGather with Written matching totalBytes completes`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Written(7))
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        val done = transport.flush()

        assertTrue(done)
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
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 7 }
        transport.write(buf1)
        transport.write(buf2)

        assertFalse(transport.flush(), "partial writev yields false")
        assertEquals(1, fake.writevCalls)

        // Second flush finds only the 6-byte remainder of buf2 (buf1
        // fully consumed in first writev). size == 1 → flushSingle path.
        assertTrue(transport.flush())
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
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertFalse(transport.flush(), "WouldBlock yields false")
        assertTrue(transport.flush(), "retry flushes the full batch (7 bytes)")
        assertEquals(2, fake.writevCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with Failed drops all buffers`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Failed(EPIPE))
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        val done = transport.flush()

        assertTrue(done)
        assertEquals(1, fake.writevCalls)
        // Pending queue cleared — second flush must be a no-op.
        assertTrue(transport.flush())
        assertEquals(1, fake.writevCalls)
        fake.assertAllConsumed()
    }
}
