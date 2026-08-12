package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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
internal class KqueueTransportFlushWaitSeamTest : KqueueTransportSeamFixture() {

    // --- awaitPendingFlush / teardown cancellation regression ---

    /**
     * Regression test for teardown cancellation: `teardownOnEventLoop` must cancel
     * any coroutine suspended in `awaitPendingFlush`.
     *
     * See `EpollTransportFlushWaitSeamTest` for the full rationale; this is
     * the macOS / kqueue counterpart exercising `KqueueIoTransport`.
     */
    @Test
    fun `awaitPendingFlush is cancelled when transport is torn down`() = runBlocking {
        eventLoop.start()

        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.WouldBlock)
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush()

        var caughtCancellation = false
        val awaitJob = launch {
            try {
                transport.awaitPendingFlush()
            } catch (_: CancellationException) {
                caughtCancellation = true
            }
        }

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
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        withTimeout(500) {
            transport.awaitPendingFlush()
        }
    }

    // --- awaitPendingFlush TOCTOU race fix ---

    /** Symmetric kqueue counterpart of the epoll TOCTOU-race regression test. */
    @Test
    fun `awaitPendingFlush resumes after concurrent EL flush via FIFO dispatch ordering`() = runBlocking {
        eventLoop.start()

        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.WouldBlock)
            enqueueWrite(fd, WriteResult.Written(4))
        }
        val transport = KqueueIoTransport(fd, eventLoop, DefaultAllocator, fake)

        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush() // WouldBlock → pendingWrites non-empty, EVFILT_WRITE registered

        // Task_A dispatched before awaitPendingFlush; FIFO guarantees it runs first.
        // Post-fix: awaitPendingFlush dispatches Task_B; Task_A drains queue, Task_B
        // sees isEmpty=true → cont.resume(Unit). Pre-fix: race between off-EL check
        // and EL Task_A completing flush → potential deadlock.
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
     * Regression test for the coalesced-flush eager run — macOS counterpart of
     * the epoll test of the same name. A caller that reaches `awaitPendingFlush`
     * while a deferred flush is queued must have that flush run inline (inside
     * the register lambda) instead of parking for the deferred runnable to fire
     * on a later drain.
     *
     * The canary task is dispatched after `flush()` queues the deferred
     * runnable and before `awaitPendingFlush` registers. With the eager run
     * the await resumes synchronously inside the register lambda — before the
     * canary executes. Without it (pre-fix) the continuation parks, the
     * deferred runnable drains and resumes it, and the resumed coroutine is
     * re-dispatched behind the canary — the canary observes execution first.
     */
    @Test
    fun `awaitPendingFlush runs a queued coalesced flush inline`() = runBlocking {
        val coalescingLoop = KqueueEventLoop(logger, flushCoalescing = true)
        try {
            coalescingLoop.start()
            val fake = FakeNativeSocket().apply {
                enqueueWrite(fd, WriteResult.Written(4))
            }
            val transport = KqueueIoTransport(fd, coalescingLoop, DefaultAllocator, fake)

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

    @Test
    fun `an awaited flush whose eager drain throws answers the caller`() = runBlocking {
        // The registration runs on the loop, and an off-loop caller reaches it
        // by dispatch. Its eager drain throws before the continuation is
        // stored, so nothing is left that could answer this caller -- and the
        // throw reaches only the loop's task drain, which logs it and moves on.
        //
        // The ordering below is what puts the registration ahead of the tick:
        // the loop is held inside a task while the caller registers, and only
        // then does that task write and flush.
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { flushThrowsOnce = InjectedFault("write refused") }
        val loop = KqueueEventLoop(logger)
        loop.start()
        try {
            val transport = KqueueIoTransport(fd, loop, tracker, fake)
            val release = CompletableDeferred<Unit>()
            val held = CompletableDeferred<Unit>()
            loop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    held.complete(Unit)
                    // Blocking the loop is the point: the caller below has to
                    // reach its dispatch while this task still owns the thread.
                    while (!release.isCompleted) {
                        // Busy-wait: this thread cannot suspend, and the window is one dispatch long.
                    }
                    val buf = tracker.allocate(WAIT_PAYLOAD_BYTES).also { it.writerIndex = WAIT_PAYLOAD_BYTES }
                    transport.write(buf)
                    transport.flush() // queues the tick behind the registration
                },
            )
            withTimeout(WAIT_TIMEOUT_MS) { held.await() }

            val answer = CompletableDeferred<Throwable?>()
            // Unconfined so the body runs here up to its own dispatch: when
            // `launch` returns, the registration is queued ahead of the tick.
            launch(Dispatchers.Unconfined) {
                answer.complete(runCatching { transport.awaitPendingFlush() }.exceptionOrNull())
            }
            release.complete(Unit)

            val thrown = withTimeout(WAIT_TIMEOUT_MS) { answer.await() }
            assertTrue(
                thrown is InjectedFault,
                "the caller must be told what the drain failed with rather than parked, got: $thrown",
            )
            assertEquals(0, tracker.outstandingCount, "the entry that drain gave back is released by the close")
        } finally {
            loop.close()
        }
    }

    private companion object {
        /** Wall-clock bound for anything this suite waits on. */
        const val WAIT_TIMEOUT_MS = 5_000L

        /** Payload size for the cases that need one queued entry. */
        const val WAIT_PAYLOAD_BYTES = 5
    }
}
