package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.socket
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [IoUringIoTransport] — synchronous
 * fallback paths only.
 *
 * io_uring's async SQE paths (`flushCqe`, `flushSendZc`,
 * `flushSendmsgZc`, `submitAsyncSend`) are driven entirely by the
 * kernel and cannot be intercepted through the [NativeSocket] seam.
 * The seam only covers:
 *
 * - [IoUringIoTransport.shutdownOutput] (non-direct-alloc path)
 * - [IoUringIoTransport.flushDirectSendSingle] (FALLBACK_CQE mode,
 *   synchronous `send()` before any EAGAIN fallback to async SQE)
 *
 * WouldBlock on `send()` triggers `submitAsyncSend`, which queues a
 * real SQE — that branch is left to integration coverage in
 * [IoUringEngineTest]. Part of the project's two-layer seam + integration testing strategy (this file covers the seam side).
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportSeamTest")
    private val capabilities = IoUringCapabilities()
    private lateinit var eventLoop: IoUringEventLoop
    private var fd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = IoUringEventLoop(logger, capabilities)
        // Disposable real socket fd — the fake intercepts every
        // byte-level syscall, so no real I/O happens on this fd.
        fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setUp" }
    }

    @AfterTest
    fun tearDown() {
        // Stop the loop before closing the fd it may still be polling.
        eventLoop.close()
        close(fd)
    }

    private fun newTransport(fake: FakeNativeSocket): IoUringIoTransport =
        IoUringIoTransport(
            fd = fd,
            eventLoop = eventLoop,
            capabilities = capabilities,
            writeModeSelector = IoModeSelectors.FALLBACK_CQE,
            allocator = DefaultAllocator,
            bufferRing = null,
            fixedFileRegistry = null,
            registeredBufferTable = DisabledRegisteredBufferRegistry,
            preAllocatedIndex = -1,
            nativeSocket = fake,
        )

    // --- shutdownOutput (non-direct-alloc path) ---

    /**
     * Returns once the loop has run everything dispatched so far.
     *
     * A marker task goes through the same FIFO queue, so when it completes the
     * work queued before it has already run. Awaiting the deferred also
     * publishes the loop thread's writes to this one — [FakeNativeSocket] is
     * documented single-threaded, so its counters must not be polled while the
     * loop may still be touching them.
     */
    private suspend fun awaitLoopDrained() {
        val marker = CompletableDeferred<Unit>()
        eventLoop.dispatch(EmptyCoroutineContext, Runnable { marker.complete(Unit) })
        withTimeout(SEAM_TIMEOUT_MILLIS) { marker.await() }
    }

    @Test
    fun `shutdownOutput with Ok invokes nativeSocket once`() = runBlocking {
        // shutdown(2) runs on the EventLoop like every other op on this fd, so
        // the loop has to be running and the assertion has to wait for it.
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = newTransport(fake)

        transport.shutdownOutput()

        awaitLoopDrained()
        assertEquals(1, fake.shutdownCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `shutdownOutput is idempotent`() = runBlocking {
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = newTransport(fake)

        transport.shutdownOutput()
        transport.shutdownOutput()
        transport.shutdownOutput()

        // All three dispatches have run by now (FIFO marker), so a lost
        // short-circuit shows up as 2 or 3 rather than passing on timing.
        awaitLoopDrained()
        assertEquals(1, fake.shutdownCalls)
    }

    @Test
    fun `shutdownOutput with Failed EPIPE does not throw`() = runBlocking {
        // The body now runs inside a dispatched task, and drainTasks catches
        // whatever a task throws. So "does not throw" can no longer be observed
        // by the call returning — assert on the loop's own guard log instead.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = IoUringEventLoop(warns, capabilities)
        loop.start()
        try {
            val fake = FakeNativeSocket().apply {
                enqueueShutdown(fd, ShutdownResult.Failed(EPIPE))
            }
            val transport = IoUringIoTransport(
                fd = fd,
                eventLoop = loop,
                capabilities = capabilities,
                writeModeSelector = IoModeSelectors.FALLBACK_CQE,
                allocator = DefaultAllocator,
                bufferRing = null,
                fixedFileRegistry = null,
                registeredBufferTable = DisabledRegisteredBufferRegistry,
                preAllocatedIndex = -1,
                nativeSocket = fake,
            )

            transport.shutdownOutput()

            val marker = CompletableDeferred<Unit>()
            loop.dispatch(EmptyCoroutineContext, Runnable { marker.complete(Unit) })
            withTimeout(SEAM_TIMEOUT_MILLIS) { marker.await() }

            assertEquals(1, fake.shutdownCalls)
            assertTrue(
                warns.messages.any { "shutdown(SHUT_WR) failed" in it },
                "the EPIPE must be reported by the transport, got: ${warns.messages}",
            )
            assertTrue(
                warns.messages.none { "dispatched task threw" in it },
                "shutdownOutput must not throw out of the dispatched task, got: ${warns.messages}",
            )
        } finally {
            loop.close()
        }
    }

    // --- Half-close ordering (deferred FIN) ---

    @Test
    fun `shutdownOutput holds the FIN back while a send SQE is outstanding`() = runBlocking {
        // EAGAIN on the single-buffer direct send hands the remainder to an
        // async SEND SQE and flush() then clears pendingWrites, so an empty
        // queue is not evidence the bytes have gone. The half-close has to
        // wait for the chain, not for the queue.
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueSend(fd, WriteResult.WouldBlock)
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = newTransport(fake)

        // Observed inside the same loop task as the half-close, so a CQE
        // arriving afterwards cannot be mistaken for the deferral working.
        val finCallsAtHalfClose = CompletableDeferred<Int>()
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = DefaultAllocator.allocate(PAYLOAD_BYTES)
                buf.writerIndex = PAYLOAD_BYTES
                transport.write(buf)
                transport.flush()
                transport.shutdownOutput()
                finCallsAtHalfClose.complete(fake.shutdownCalls)
            },
        )
        assertEquals(
            0,
            withTimeout(SEAM_TIMEOUT_MILLIS) { finCallsAtHalfClose.await() },
            "FIN must wait for the outstanding send",
        )
    }

    @Test
    fun `write after shutdownOutput is discarded rather than queued`() = runBlocking {
        // The caller declared it had nothing more to send, so a later write
        // must not slip in behind the FIN. Ownership was still transferred,
        // so the buffer has to be released rather than leaked.
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = newTransport(fake)
        val tracker = TrackingAllocator()

        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                transport.shutdownOutput()
                val buf = tracker.allocate(PAYLOAD_BYTES)
                buf.writerIndex = PAYLOAD_BYTES
                transport.write(buf)
                transport.flush()
            },
        )
        awaitLoopDrained()

        assertEquals(1, fake.shutdownCalls)
        assertEquals(0, fake.sendCalls, "nothing may be sent after the FIN")
        assertEquals(0, tracker.outstandingCount, "the discarded write must still be released")
        fake.assertAllConsumed()
    }

    // --- flushDirectSendSingle (FALLBACK_CQE, single-buffer synchronous path) ---

    @Test
    fun `flushDirectSendSingle with Written completes in one call`() {
        val fake = FakeNativeSocket().apply {
            enqueueSend(fd, WriteResult.Written(5))
        }
        val transport = newTransport(fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(1, fake.sendCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushDirectSendSingle with partial Written loops until complete`() {
        // Kernel returns 3 then 2 of a 5-byte send; engine loops until
        // all bytes are transferred.
        val fake = FakeNativeSocket().apply {
            enqueueSend(
                fd,
                WriteResult.Written(3),
                WriteResult.Written(2),
            )
        }
        val transport = newTransport(fake)

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(2, fake.sendCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `flushDirectSendSingle with Failed invokes onReadClosed`() {
        // ECONNRESET during send: the engine must surface this as a
        // read-closed event so the pipeline tears down. Previously the
        // buffer was silently released and flush returned "complete",
        // leaving the pipeline unaware — see the fix comment in
        // flushDirectSendSingle.
        val fake = FakeNativeSocket().apply {
            enqueueSend(fd, WriteResult.Failed(ECONNRESET))
        }
        val transport = newTransport(fake)

        var readClosedFired = 0
        transport.onReadClosed = { readClosedFired++ }

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(1, fake.sendCalls)
        assertEquals(1, readClosedFired, "Failed must route through onReadClosed")
        fake.assertAllConsumed()
    }

    @Test
    fun `flushDirectSendSingle with Failed zero errno is logged but closes connection`() {
        // send()==0 → Failed(errno=0). Distinct warn message path in
        // IoUringIoTransport — the dedicated "returned 0 unexpectedly"
        // branch runs before falling through to the shared teardown.
        val fake = FakeNativeSocket().apply {
            enqueueSend(fd, WriteResult.Failed(errno = 0))
        }
        val transport = newTransport(fake)

        var readClosedFired = 0
        transport.onReadClosed = { readClosedFired++ }

        val buf = DefaultAllocator.allocate(16)
        buf.writerIndex = 5
        transport.write(buf)

        assertTrue(transport.flush())
        assertEquals(1, readClosedFired, "send()==0 must still tear down the connection")
    }

    // --- awaitPendingFlush (teardown cancellation regression) ---

    /**
     * `awaitPendingFlush` returns immediately when no async flush is in flight
     * (`asyncFlushPending == false`). This is the common case after a
     * synchronous `send()` completes or when the queue is empty.
     *
     * The teardown-cancellation path (`asyncFlushPending == true`) requires a
     * real io_uring ring to trigger `submitAsyncSend`; that path is covered by
     * the integration tests in `IoModeTest` and `IoUringEngineReadWriteTest`.
     */
    @Test
    fun `awaitPendingFlush returns immediately when no async flush is pending`() = runBlocking {
        // EL must be started: the fix dispatches check+register to EL even for
        // the empty-queue fast path, so the EL thread must be running to process
        // the lambda and resume the continuation.
        eventLoop.start()

        val fake = FakeNativeSocket()
        val transport = newTransport(fake)

        withTimeout(500) {
            transport.awaitPendingFlush()
        }
    }

    // --- awaitPendingFlush TOCTOU race fix ---

    /**
     * Verifies the TOCTOU-race fix for the `asyncFlushPending = false` fast path: when
     * the EL-dispatched check+register lambda sees no pending flush, the
     * continuation is resumed immediately rather than stored (post-fix invariant).
     *
     * The full TOCTOU race (asyncFlushPending=true → write CQE fires between check
     * and cont store → deadlock) requires a real io_uring ring to trigger
     * `submitAsyncSend`; that path is covered by the integration tests in
     * `IoModeTest` and `IoUringEngineReadWriteTest`.
     */
    @Test
    fun `awaitPendingFlush dispatches to EL and resumes when no flush is pending`() = runBlocking {
        eventLoop.start()

        val fake = FakeNativeSocket()
        val transport = newTransport(fake)

        // asyncFlushPending is false. Post-fix: dispatches to EL, lambda sees
        // !asyncFlushPending → cont.resume(Unit). Must not hang.
        withTimeout(2000) {
            transport.awaitPendingFlush()
        }
    }

    private companion object {
        const val SEAM_TIMEOUT_MILLIS = 5_000L

        /** Payload size for the half-close ordering tests. */
        const val PAYLOAD_BYTES = 5
    }
}
