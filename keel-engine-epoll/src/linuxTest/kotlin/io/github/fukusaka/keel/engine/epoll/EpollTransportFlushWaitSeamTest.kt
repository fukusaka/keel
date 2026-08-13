@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.readiness.Interest
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.PosixIoTransport
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam tests for `awaitPendingFlush`: what ends the wait, and what must not
 * park on a flush no future event can complete.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollTransportFlushWaitSeamTest : EpollTransportSeamFixture() {

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
        val transport = PosixIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush() // returns false (WouldBlock), EPOLLOUT pending

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
        val transport = PosixIoTransport(fd, eventLoop, DefaultAllocator, fake)

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
        val transport = PosixIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush() // WouldBlock → pendingWrites non-empty, EPOLLOUT registered

        // Task_A: simulate EPOLLOUT firing — drains pendingWrites.
        // Dispatched before awaitPendingFlush, guaranteed to be queued first.
        // Post-fix: awaitPendingFlush dispatches Task_B; FIFO ensures Task_A runs
        // first (drains queue), then Task_B sees empty → cont.resume(Unit). ✓
        // Pre-fix: check+store off-EL; Task_A fires between them → deadlock (race).
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable { transport.onReady(Interest.WRITE) },
        )

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
            val transport = PosixIoTransport(fd, coalescingLoop, DefaultAllocator, fake)

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
