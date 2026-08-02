package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.LoopParticipant
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.EBADF
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.dup
import platform.posix.errno
import platform.posix.socket
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [EpollIoTransport] driving the fake
 * [io.github.fukusaka.keel.native.posix.NativeSocket] directly.
 *
 * Part of the project's two-layer seam + integration testing strategy:
 * these seam tests exhaust the errno-branch space of the synchronous
 * code paths (`shutdownOutput`, `flush` / `flushSingle` / `flushGather`)
 * without relying on a running EventLoop or kernel readiness. Paths
 * that require real readiness events (`onReadable`) remain covered by
 * the integration tests.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollTransportSeamTest {

    private val logger = NoopLoggerFactory.logger("EpollTransportSeamTest")
    private lateinit var eventLoop: EpollEventLoop
    private var fd: Int = -1

    @BeforeTest
    fun setUp() {
        // Disable the per-tick flush coalescing so `flush()` delegates
        // synchronously to `performFlush()` — the seam tests exercise
        // errno branches / syscall behaviour and are not driving an EL
        // thread that could drain the deferred runnable. Under the opt-out
        // the semantics reduce to pre-#900 immediate-send.
        eventLoop = EpollEventLoop(logger, flushCoalescing = false)
        // Disposable real socket fd — needed for epoll_ctl in WouldBlock
        // branch (`registerWriteCallback`). No real I/O happens; the fake
        // intercepts every byte-level syscall.
        fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setUp" }
    }

    @AfterTest
    fun tearDown() {
        // Stop the loop first: the transport registered [fd] with it, so closing
        // the descriptor while the loop thread is still polling or arming it is
        // the recycled-fd hazard the engine funnels exist to avoid. Harmless
        // until these tests started the loop; now it is not.
        eventLoop.close()
        if (fd >= 0) close(fd)
    }

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
        withTimeout(SEAM_TIMEOUT_MS) { marker.await() }
    }

    // --- shutdownOutput ---

    @Test
    fun `shutdownOutput with Ok response invokes nativeSocket once`() = runBlocking {
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

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
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        transport.shutdownOutput()
        transport.shutdownOutput()
        transport.shutdownOutput()

        // Second and third calls must short-circuit on the `outputShutdown`
        // flag — fake must see exactly one invocation. Settle first so a lost
        // guard shows up as 2 or 3 rather than passing on timing.
        awaitLoopDrained()
        assertEquals(1, fake.shutdownCalls)
    }

    @Test
    fun `shutdownOutput with Failed EPIPE does not throw`() = runBlocking {
        // The body now runs inside a dispatched task, and drainTasks catches
        // whatever a task throws. So "does not throw" can no longer be observed
        // by the call returning — assert on the loop's own guard log instead:
        // a throw would surface as "dispatched task threw", and the failure has
        // to surface as the transport's own shutdown warning.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = EpollEventLoop(warns, flushCoalescing = false)
        loop.start()
        try {
            val fake = FakeNativeSocket().apply {
                enqueueShutdown(fd, ShutdownResult.Failed(EPIPE))
            }
            val transport = EpollIoTransport(fd, loop, DefaultAllocator, fake)

            transport.shutdownOutput()

            val marker = CompletableDeferred<Unit>()
            loop.dispatch(EmptyCoroutineContext, Runnable { marker.complete(Unit) })
            withTimeout(SEAM_TIMEOUT_MS) { marker.await() }

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
    fun `shutdownOutput holds the FIN back while the flush is stalled`() = runBlocking {
        // A half-close issued on top of buffered output must not overtake it:
        // the FIN goes out only once the bytes have been written. The two
        // WouldBlock results keep the socket stalled across both the caller's
        // flush and the retry the half-close itself drives.
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.WouldBlock, WriteResult.Written(PAYLOAD_BYTES))
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        // Observed inside the same loop task as the half-close. The fixture fd is
        // an unconnected socket, so arming it for write readiness can make the
        // loop report it writable straight away — reading the counter from a
        // later task would race that retry rather than test the deferral.
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
            withTimeout(SEAM_TIMEOUT_MS) { finCallsAtHalfClose.await() },
            "FIN must wait for the stalled write",
        )

        // Socket becomes writable — the retry drains the queue and releases the FIN.
        // Harmless if the loop already delivered write readiness on its own: the
        // second pass finds the queue empty and the FIN already sent.
        eventLoop.dispatch(EmptyCoroutineContext, Runnable { transport.onReady(Interest.WRITE) })
        awaitLoopDrained()
        assertEquals(1, fake.shutdownCalls, "FIN must follow the completed write")
        fake.assertAllConsumed()
    }

    @Test
    fun `write after shutdownOutput is discarded rather than queued`() = runBlocking {
        // The caller declared it had nothing more to send, so a later write
        // must not slip in behind the FIN. The buffer's ownership was still
        // transferred, so it has to be released rather than leaked.
        eventLoop.start()
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)
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
        assertEquals(0, fake.writeCalls, "nothing may be sent after the FIN")
        assertEquals(0, tracker.outstandingCount, "the discarded write must still be released")
        fake.assertAllConsumed()
    }

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

    // --- awaitPendingFlush / teardown cancellation regression ---

    /**
     * Regression test for teardown cancellation: `teardownOnEventLoop` must cancel
     * any coroutine suspended in `awaitPendingFlush` so the caller
     * does not hang indefinitely.
     *
     * Before the fix, `teardownOnEventLoop` cleared `pendingWrites` but
     * never touched `flushContinuation`, leaving `awaitPendingFlush`
     * suspended forever. The fix cancels the continuation so the
     * caller receives `CancellationException` and can proceed.
     *
     * The EventLoop is started (pthread) so the dispatched
     * `teardownOnEventLoop` task is actually executed when
     * `transport.close()` is called from outside the EventLoop thread.
     */
    @Test
    fun `awaitPendingFlush is cancelled when transport is torn down`() = runBlocking {
        // Start the EventLoop pthread so dispatched tasks run.
        eventLoop.start()

        val fake = FakeNativeSocket().apply {
            // EAGAIN on the first write — flush returns false, awaitPendingFlush suspends.
            enqueueWrite(fd, WriteResult.WouldBlock)
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush()  // returns false (WouldBlock), EPOLLOUT pending

        // Start awaitPendingFlush in a background coroutine; it must be
        // unblocked (via CancellationException) when the transport closes.
        var caughtCancellation = false
        val awaitJob = launch {
            try {
                transport.awaitPendingFlush()
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }

        // Close the transport from outside the EventLoop thread; transport.close()
        // dispatches teardownOnEventLoop() to the EventLoop. The EventLoop thread
        // picks it up, cancels flushContinuation, and awaitJob completes.
        // withTimeout guards the test against an infinite hang (the pre-fix bug).
        withTimeout(2000) {
            transport.close()
            awaitJob.join()
        }

        assertTrue(caughtCancellation, "awaitPendingFlush must be cancelled on close")
    }

    @Test
    fun `awaitPendingFlush returns immediately when pending queue is empty`() = runBlocking {
        // EL must be started: the fix dispatches check+register to EL even for
        // the empty-queue fast path, so the EL thread must be running to process
        // the lambda and resume the continuation.
        eventLoop.start()

        val fake = FakeNativeSocket()
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        withTimeout(500) {
            transport.awaitPendingFlush()
        }
    }

    // --- awaitPendingFlush TOCTOU race fix ---

    /**
     * Regression test for the awaitPendingFlush TOCTOU race: verifies that `awaitPendingFlush` correctly
     * handles the case where flush completes on the EventLoop thread while
     * the continuation is being registered.
     *
     * **The race (pre-fix)**: the check (`pendingWrites.isEmpty()`) and the
     * store (`flushContinuation = cont`) were both performed off-EL, creating
     * a TOCTOU window:
     * 1. Caller off-EL: `pendingWrites.isEmpty()` → false (writes pending)
     * 2. EL: `onReady(WRITE)` → `flush()` succeeds → `flushContinuation` is null
     *    (cont not yet stored) → no resume; EPOLLOUT removed
     * 3. Caller off-EL: `flushContinuation = cont` → stored after EL already
     *    passed its null check → **permanent deadlock**
     *
     * **The fix**: `awaitPendingFlush` dispatches the check+register lambda to
     * the EventLoop. When the lambda runs on the EL thread, the check and store
     * are atomic from the EL's perspective: if `pendingWrites` is already empty,
     * `cont.resume(Unit)` is called immediately instead of storing the cont.
     *
     * **Test approach**: the FIFO task queue guarantees that Task_A (onReady,
     * dispatched below) precedes Task_B (check+register dispatched by
     * awaitPendingFlush). Post-fix: Task_A drains pendingWrites; Task_B sees the
     * empty queue and resumes the continuation — always correct. Pre-fix: the
     * check+register runs off-EL; if Task_A fires between the isEmpty check and
     * the cont store, the race manifests. The narrow race window makes this
     * non-deterministic in a unit test; the regression evidence is the bench
     * result (30 req/s → ~490 req/s after fix).
     */
    @Test
    fun `awaitPendingFlush resumes after concurrent EL flush via FIFO dispatch ordering`() = runBlocking {
        eventLoop.start()

        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.WouldBlock)
            enqueueWrite(fd, WriteResult.Written(4))
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush()  // WouldBlock → pendingWrites non-empty, EPOLLOUT registered

        // Task_A: simulate EPOLLOUT firing — drains pendingWrites.
        // Dispatched before awaitPendingFlush, guaranteed to be queued first.
        // Post-fix: awaitPendingFlush dispatches Task_B; FIFO ensures Task_A runs
        // first (drains queue), then Task_B sees empty → cont.resume(Unit). ✓
        // Pre-fix: check+store off-EL; Task_A fires between them → deadlock (race).
        eventLoop.dispatch(EmptyCoroutineContext, Runnable {
            transport.onReady(Interest.WRITE)
        })

        withTimeout(2000) {
            transport.awaitPendingFlush()
        }
    }

    // --- coalesced-flush eager run in awaitPendingFlush ---

    /**
     * Regression test for the coalesced-flush eager run: a caller that reaches
     * `awaitPendingFlush` while a deferred flush is queued must have that flush
     * run inline (inside the register lambda) instead of parking for the
     * deferred runnable to fire on a later drain.
     *
     * The canary task is dispatched after `flush()` queues the deferred
     * runnable and before `awaitPendingFlush` registers. With the eager run
     * the await resumes synchronously inside the register lambda — before the
     * canary executes. Without it (pre-fix) the continuation parks, the
     * deferred runnable drains and resumes it, and the resumed coroutine is
     * re-dispatched behind the canary — the canary observes execution first.
     *
     * The one-EL-tick round-trip this pins was measured as a ~-25% throughput
     * regression on high-concurrency 100 KB responses (every producer hitting
     * the backpressure gate paid the extra tick).
     */
    @Test
    fun `awaitPendingFlush runs a queued coalesced flush inline`() = runBlocking {
        val coalescingLoop = EpollEventLoop(logger, flushCoalescing = true)
        try {
            coalescingLoop.start()
            val fake = FakeNativeSocket().apply {
                enqueueWrite(fd, WriteResult.Written(4))
            }
            val transport = EpollIoTransport(fd, coalescingLoop, DefaultAllocator, fake)

            var canaryRanWhenAwaitReturned = true
            val job = launch(coalescingLoop) {
                var canaryRan = false
                val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
                transport.write(buf)
                transport.flush() // coalescing on → defers, flushScheduled = true
                coalescingLoop.dispatch(EmptyCoroutineContext, Runnable { canaryRan = true })
                transport.awaitPendingFlush()
                canaryRanWhenAwaitReturned = canaryRan
            }
            withTimeout(2000) { job.join() }

            assertFalse(
                canaryRanWhenAwaitReturned,
                "awaitPendingFlush must run the queued coalesced flush inline, not wait one EL tick",
            )
            assertEquals(1, fake.writeCalls)
            fake.assertAllConsumed()
        } finally {
            coalescingLoop.close()
        }
    }

    // --- Close after the loop has stopped (caller-thread teardown) ---

    @Test
    fun `close after the loop stopped still releases the fd and the pending buffers`() = runBlocking {
        // A Coroutine-mode caller closes its channel after engine shutdown.
        // The loop is gone, so the teardown cannot be dispatched; it has to
        // run on the caller instead — otherwise the fd and the stranded
        // write buffers survive to process exit.
        eventLoop.start()
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply {
            // Strand one buffer in pendingWrites: the flush stalls on
            // WouldBlock and the loop stops before any retry.
            enqueueWrite(fd, WriteResult.WouldBlock)
        }
        val transport = EpollIoTransport(fd, eventLoop, tracker, fake)
        val queued = CompletableDeferred<Unit>()
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = tracker.allocate(PAYLOAD_BYTES)
                buf.writerIndex = PAYLOAD_BYTES
                transport.write(buf)
                transport.flush()
                queued.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { queued.await() }
        eventLoop.close()

        transport.close()

        val closedFd = fd
        fd = -1 // ownership passed to the transport, which closed it; tearDown must not close the number again
        val probe = dup(closedFd)
        if (probe >= 0) close(probe)
        assertEquals(-1, probe, "the fd must be closed on the caller when the loop is gone")
        assertEquals(EBADF, errno, "closed, not fd-table exhaustion: the probe must fail with EBADF")
        assertEquals(0, tracker.outstandingCount, "the stranded pending write must be released")
    }

    // --- entry points a stopped loop used to swallow ---

    @Test
    fun `a FIN deferred behind buffered writes is reported when the loop stops first`() = runBlocking {
        // The half-close is held back until the writes drain, and the drain
        // needs a loop that polls. When the loop stops first, nothing calls
        // sendFinIfDrained again -- the FIN is never sent and, before this,
        // never mentioned either: the same "no FIN, no error, no log" the
        // quiescent case was fixed for, surviving in the one window that is
        // deliberately routed to dispatch.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = EpollEventLoop(warns, flushCoalescing = false)
        loop.start()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = EpollIoTransport(fd, loop, DefaultAllocator, fake)

        // Buffered and stalled, then half-closed -- all on the loop, so the FIN
        // is genuinely deferred rather than refused.
        val deferred = CompletableDeferred<Unit>()
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = DefaultAllocator.allocate(PAYLOAD_BYTES).also { it.writerIndex = PAYLOAD_BYTES }
                transport.write(buf)
                transport.shutdownOutput()
                deferred.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { deferred.await() }

        loop.close()

        assertEquals(0, fake.shutdownCalls, "premise: the FIN never went out")
        assertTrue(
            warns.messages.any { "deferred the FIN behind buffered writes" in it },
            "an abandoned half-close must be reported, not left silent: ${warns.messages}",
        )
    }

    @Test
    fun `a half-close taken during the stop sweep reports its abandoned FIN`() = runBlocking {
        // The other ordering, and the one the sweep cannot catch: the deferral
        // does not exist yet when the sweep walks this transport, and is created
        // afterwards -- by a participant closing its own output as it is told
        // the loop stopped. That runs on the loop thread with the loop already
        // finishing, so it takes the in-loop branch, which needs the report just
        // as much as the dispatched one.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = EpollEventLoop(warns, flushCoalescing = false)
        loop.start()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = EpollIoTransport(fd, loop, DefaultAllocator, fake)

        val buffered = CompletableDeferred<Unit>()
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = DefaultAllocator.allocate(PAYLOAD_BYTES).also { it.writerIndex = PAYLOAD_BYTES }
                transport.write(buf)
                buffered.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { buffered.await() }

        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    transport.shutdownOutput()
                }
            },
        )

        loop.close()

        assertEquals(0, fake.shutdownCalls, "premise: the FIN never went out")
        assertTrue(
            warns.messages.any { "deferred the FIN behind buffered writes" in it },
            "a half-close taken during the sweep must report too: ${warns.messages}",
        )
    }

    @Test
    fun `shutdownOutput on a stopped loop is refused with a warning`() = runBlocking {
        // It used to dispatch onto a queue nothing drains: no FIN, no error, no
        // log -- the peer waited for an EOF that was never coming. The FIN still
        // is not sent (the buffered writes it waits for will never drain, and
        // issuing it here would race a concurrent close for the fd), but the
        // request is now reported instead of disappearing.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = EpollEventLoop(warns, flushCoalescing = false)
        loop.start()
        val fake = FakeNativeSocket().apply { enqueueShutdown(fd, ShutdownResult.Ok) }
        val transport = EpollIoTransport(fd, loop, DefaultAllocator, fake)
        loop.close()

        // shutdownOutput() is non-blocking on every path, so this budget is a
        // real one rather than a formality -- it bounds the call itself, not
        // just the suite's house style.
        withTimeout(SEAM_TIMEOUT_MS) { transport.shutdownOutput() }

        assertEquals(0, fake.shutdownCalls, "no FIN is issued off the loop that used to order it")
        assertTrue(
            warns.messages.any { "shutdownOutput() on a stopped EventLoop" in it },
            "and the refusal must be reported, not silent: ${warns.messages}",
        )
    }

    @Test
    fun `awaitPendingFlush entered after the loop stopped is cancelled instead of parking`() = runBlocking {
        // The register Runnable would land on a dead queue, so the continuation
        // was never even stored -- the one shape close() cannot rescue, because
        // there is nothing for it to cancel.
        eventLoop.start()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)
        val queued = CompletableDeferred<Unit>()
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
                transport.write(buf)
                transport.flush()
                queued.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { queued.await() }
        eventLoop.close()

        var cancelled = false
        withTimeout(SEAM_TIMEOUT_MS) {
            try {
                transport.awaitPendingFlush()
            } catch (_: CancellationException) {
                cancelled = true
            }
        }

        assertTrue(cancelled, "a caller arriving after the loop stopped must not park forever")
    }

    @Test
    fun `the stop sweep ends a caller already parked in awaitPendingFlush`() = runBlocking {
        // Parked before the stop, on a live dispatcher, and never closed: the
        // sweep is the only thing that reaches it while the channel is open.
        eventLoop.start()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)
        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush()

        var cancelled = false
        val waiter = launch(Dispatchers.Default) {
            try {
                transport.awaitPendingFlush()
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        withTimeout(SEAM_TIMEOUT_MS) {
            while (!transport.hasFlushWaiter()) delay(POLL_MS)
        }

        eventLoop.close() // runs the sweep, which tells every participant

        withTimeout(SEAM_TIMEOUT_MS) { waiter.join() }
        assertTrue(cancelled, "the sweep must end the write-side wait, not only the read side")
    }

    @Test
    fun `awaitPendingFlush on a stopped loop is cancelled with a reason rather than silently resumed`() = runBlocking {
        // Pins the deliberate choice, so it is not "improved" back into a race.
        // Deciding whether the queue is really empty means reading pending state
        // from off the loop, where a concurrent close() is emptying it -- and a
        // stale read there reports a flush complete whose bytes were dropped.
        // Cancelling is the fail-safe answer even for a caller that had nothing
        // queued, and the cause says which fd and why.
        eventLoop.start()
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, FakeNativeSocket())
        eventLoop.close()

        val cause = withTimeout(SEAM_TIMEOUT_MS) {
            runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
        }

        assertTrue(cause is CancellationException, "a stopped loop must not report the flush complete, got: $cause")
        assertTrue(
            cause.message?.contains("fd=$fd") == true,
            "the cancellation must name the fd and the reason, got: ${cause.message}",
        )
    }

    @Test
    fun `a flush awaited from inside the stop sweep is cancelled instead of parking`() = runBlocking {
        // The window the caller-side check cannot see. The loop has stopped
        // polling but is not yet quiescent, so work dispatched now still runs --
        // in the drain the sweep performs *after* it has walked the
        // participants. A continuation stored there is one the sweep has
        // already been past, so nothing is left to end the wait.
        eventLoop.start()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)
        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush()

        val outcome = CompletableDeferred<String>()
        eventLoop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    // Queued from inside the participant walk, so it lands in
                    // the drain that follows it.
                    CoroutineScope(eventLoop).launch {
                        try {
                            transport.awaitPendingFlush()
                            outcome.complete("resumed")
                        } catch (_: CancellationException) {
                            outcome.complete("cancelled")
                        }
                    }
                }
            },
        )

        eventLoop.close()

        assertEquals(
            "cancelled",
            withTimeout(SEAM_TIMEOUT_MS) { outcome.await() },
            "a flush awaited after the sweep has passed must not park",
        )
    }

    @Test
    fun `a stopped loop reports that it can no longer be dispatched to`() = runBlocking {
        // What the pipeline asks before handing over a buffer whose ownership
        // it has taken. The release itself is pinned in the pipeline's own
        // suite; this pins that this engine answers truthfully -- open, but
        // with a dispatcher that will never run anything again.
        //
        // The budget is declarative here: close() joins the loop thread with no
        // suspension point, so withTimeout cannot cut a loop that will not
        // leave its body -- the job timeout does. Stated anyway so the whole
        // suite reads one way.
        withTimeout(SEAM_TIMEOUT_MS) {
            eventLoop.start()
            val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, FakeNativeSocket())
            assertTrue(transport.canDispatchToOwningContext, "a live loop still takes work")

            eventLoop.close()

            assertTrue(transport.isOpen, "premise: the transport is still open, only its loop stopped")
            assertFalse(transport.canDispatchToOwningContext, "a stopped loop must say so")
        }
    }

    private companion object {
        const val SEAM_TIMEOUT_MS = 5_000L

        /** Poll interval while waiting for a waiter to reach its park. */
        const val POLL_MS = 5L

        /** Payload size for the half-close ordering tests. */
        const val PAYLOAD_BYTES = 5
    }
}
