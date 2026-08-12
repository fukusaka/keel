package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam tests for [KqueueIoTransport]'s flush paths — the single-buffer
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
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
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
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(2, fake.writeCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushSingle with WouldBlock re-enqueues remainder`() {
        val fake = FakeNativeSocket().apply {
            enqueueWrite(
                fd,
                WriteResult.Written(3),
                WriteResult.WouldBlock,
                WriteResult.Written(2),
            )
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertFalse(transport.flush())
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
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(1, fake.writeCalls)
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
        val transport = KqueueIoTransport(fd, eventLoop, tracker, fake)

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
        val transport = KqueueIoTransport(fd, eventLoop, tracker, fake)

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
        // way. No engine-built loop can get here -- each wraps the configured
        // factory in a guard that swallows what `rawLog` throws -- so this
        // constructs the loop with a raw `Logger` to reach it. What it pins is
        // the order: the release is the obligation, the log is the report.
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.Failed(ECONNRESET)) }
        val loop = KqueueEventLoop(throwingWarnLogger(), flushCoalescing = false)
        try {
            val transport = KqueueIoTransport(fd, loop, tracker, fake)
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
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        assertTrue(transport.flush())
        assertEquals(0, fake.writeCalls)
        assertEquals(0, fake.writevCalls)
    }

    // --- flush / flushGather (writev) ---

    @Test
    fun `flushGather with Written matching totalBytes completes`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Written(7))
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertTrue(transport.flush())
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
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 7 }
        transport.write(buf1)
        transport.write(buf2)

        assertFalse(transport.flush())
        assertEquals(1, fake.writevCalls)

        assertTrue(transport.flush())
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
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertFalse(transport.flush())
        assertTrue(transport.flush(), "retry flushes the full batch")
        assertEquals(2, fake.writevCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushGather with Failed drops all buffers`() {
        val fake = FakeNativeSocket().apply {
            enqueueWritev(fd, WriteResult.Failed(EPIPE))
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf1 = DefaultAllocator.allocate(16).also { it.writerIndex = 3 }
        val buf2 = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf1)
        transport.write(buf2)

        assertTrue(transport.flush())
        assertEquals(1, fake.writevCalls)
        assertTrue(transport.flush())
        assertEquals(1, fake.writevCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `a scheduled flush whose write throws ends the connection`() {
        // The coalesced tick is the drain with nobody to tell: its throw would
        // reach the loop's task drain, which logs it and moves on, leaving the
        // transport open with a re-queued entry nothing will send. This loop
        // keeps coalescing on -- the shipped default, and the only way to reach
        // the tick -- rather than the fixture's opt-out.
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { flushThrowsOnce = InjectedFault("write refused") }
        val loop = KqueueEventLoop(logger)
        loop.start()
        try {
            val transport = KqueueIoTransport(fd, loop, tracker, fake)
            val inactive = CompletableDeferred<Unit>()
            transport.onReadClosed = { inactive.complete(Unit) }

            loop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    val buf = tracker.allocate(SEAM_PAYLOAD_BYTES).also { it.writerIndex = SEAM_PAYLOAD_BYTES }
                    transport.write(buf)
                    transport.flush()
                },
            )

            runBlocking {
                withTimeout(SEAM_TIMEOUT_MS) { inactive.await() }
                // The waiter would park on a drain nothing can finish; the
                // connection this ends is what answers it instead.
                assertFailsWith<CancellationException>("a caller must be answered, not left parked") {
                    withTimeout(SEAM_TIMEOUT_MS) { transport.awaitPendingFlush() }
                }
            }
            assertEquals(1, fake.writeCalls, "the write must have been attempted for this test to mean anything")
            assertEquals(0, tracker.outstandingCount, "the entry the failed drain re-queued is released by that close")
        } finally {
            loop.close()
        }
    }

    @Test
    fun `a dispatched half-close whose flush throws ends the connection`() {
        // `shutdownOutput` from off the loop: the caller has already returned,
        // so the throw its flush raises reaches only the loop's task drain.
        // Left there it leaves the transport open holding the entry the failed
        // flush gave back, with a FIN deferred behind writes nothing will send.
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { flushThrowsOnce = InjectedFault("write refused") }
        val loop = KqueueEventLoop(logger, flushCoalescing = false)
        loop.start()
        try {
            val transport = KqueueIoTransport(fd, loop, tracker, fake)
            val inactive = CompletableDeferred<Unit>()
            transport.onReadClosed = { inactive.complete(Unit) }

            loop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    val buf = tracker.allocate(SEAM_PAYLOAD_BYTES).also { it.writerIndex = SEAM_PAYLOAD_BYTES }
                    transport.write(buf)
                },
            )
            // From this thread, so the half-close is dispatched rather than run here.
            transport.shutdownOutput()

            runBlocking { withTimeout(SEAM_TIMEOUT_MS) { inactive.await() } }
            assertEquals(0, fake.shutdownCalls, "the bytes never went out, so no FIN may claim they had")
            assertEquals(0, tracker.outstandingCount, "and the entry the failed flush gave back is released")
        } finally {
            loop.close()
        }
    }

    @Test
    fun `a completed flush whose callback throws ends the connection`() {
        // The tick's tail is inside the guard too, not just the drain: it
        // resumes a waiter, calls back into user code and decides the FIN. A
        // throw from any of those leaves the same connection open with nobody
        // told -- and the FIN, decided after the callback, silently dropped.
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.Written(SEAM_PAYLOAD_BYTES)) }
        val loop = KqueueEventLoop(logger)
        loop.start()
        try {
            val transport = KqueueIoTransport(fd, loop, tracker, fake)
            val inactive = CompletableDeferred<Unit>()
            transport.onReadClosed = { inactive.complete(Unit) }
            transport.onFlushComplete = { throw InjectedFault("the flush callback refused") }

            loop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    val buf = tracker.allocate(SEAM_PAYLOAD_BYTES).also { it.writerIndex = SEAM_PAYLOAD_BYTES }
                    transport.write(buf)
                    transport.flush()
                },
            )

            runBlocking { withTimeout(SEAM_TIMEOUT_MS) { inactive.await() } }
            assertEquals(1, fake.writeCalls, "the write itself must have succeeded for this case to mean anything")
            assertEquals(0, tracker.outstandingCount, "the sent entry is released by the write, not stranded")
            fake.assertAllConsumed()
        } finally {
            loop.close()
        }
    }
}
