package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
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
        close(fd)
        eventLoop.close()
    }

    // --- shutdownOutput ---

    @Test
    fun `shutdownOutput with Ok response invokes nativeSocket once`() {
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        transport.shutdownOutput()

        assertEquals(1, fake.shutdownCalls)
        fake.assertAllConsumed()
    }

    @Test
    fun `shutdownOutput is idempotent`() {
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        transport.shutdownOutput()
        transport.shutdownOutput()
        transport.shutdownOutput()

        // Second and third calls must short-circuit on the `outputShutdown`
        // flag — fake must see exactly one invocation.
        assertEquals(1, fake.shutdownCalls)
    }

    @Test
    fun `shutdownOutput with Failed EPIPE does not throw`() {
        // EPIPE here is representative of "peer already gone" — the
        // engine must log-and-continue rather than propagate the error,
        // because shutdown is a best-effort notification.
        val fake = FakeNativeSocket().apply {
            enqueueShutdown(fd, ShutdownResult.Failed(EPIPE))
        }
        val transport = EpollIoTransport(fd, eventLoop, DefaultAllocator, fake)

        transport.shutdownOutput()

        assertEquals(1, fake.shutdownCalls)
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
            transport.onReady(EpollEventLoop.Interest.WRITE)
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
}
