package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.logging.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.posix.EINVAL
import platform.posix.ENOMEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for the stop sweep: the ledgers are closed when it runs, and the
 * sweep is a fixed point — nothing it triggers can re-open them.
 */
@OptIn(InternalReadinessEngineApi::class)
internal class PosixReadinessLoopSweepTest : AbstractPosixReadinessEventLoopFixture() {

    // --- Ledgers closed after the sweep (the sweep is a fixed point) ---
    //
    // The sweep empties both ledgers and tells every surviving listener. What it
    // did not do was stop the ledgers accepting more: an append landing after it
    // -- from a listener re-registering out of `onLoopStopped`, or from a task
    // the sweep's own final drain runs -- went into a map nothing reads again.
    // The entry is never dispatched, never swept, and holds its transport,
    // channel and pipeline graph for as long as the stopped loop object lives.
    //
    // Closing is done inside the sweep's own critical section, so "swept" and
    // "closed" are one atomic step: nothing can slip between them. Both append
    // paths already run under that same lock, so the check costs a plain field
    // read on a lock the caller is holding anyway -- no new atomic, nothing on
    // the per-readiness-event path.

    @Test
    fun `a suspend registration after the sweep is refused and its waiter cancelled`() = loopTest { loop ->
        loop.failRemainingWaiters()

        val cancelled = CompletableDeferred<Throwable?>()
        var handlerRan = false
        launch {
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val reg = loop.registerWaiter(FD, Interest.READ, cont)
                    // The shape awaitWriteReady uses: the handler is installed
                    // *after* register returns, so it is installed on a
                    // continuation this call already cancelled. It has to run
                    // anyway -- on the connect path it is what closes the fd,
                    // and a refusal that skipped it would leak the descriptor
                    // it was holding.
                    cont.invokeOnCancellation {
                        loop.unregister(reg)
                        handlerRan = true
                    }
                }
                cancelled.complete(null)
            } catch (t: Throwable) {
                cancelled.complete(t)
            }
        }

        val cause = cancelled.await()
        assertTrue(handlerRan, "the cancellation handler must run: on the connect path it closes the fd")
        assertTrue(
            cause is CancellationException,
            "a waiter the loop can never arm must end, not park: got $cause",
        )
        assertFalse(loop.waiters(FD, Interest.READ), "and nothing is left in a ledger nobody reads")
        assertTrue(loop.armed.isEmpty(), "and no arm is issued for it")
    }

    @Test
    fun `a callback registration after the sweep is refused without arming`() = loopTest { loop ->
        loop.failRemainingWaiters()

        loop.registerCallback(FD, Interest.READ, RecordingListener())

        assertFalse(
            loop.hasCallbackRegistration(FD, Interest.READ),
            "the callback ledger is closed; an entry here is never dispatched and never swept again",
        )
        assertTrue(loop.armedCallbacks.isEmpty(), "and no arm is issued for it")
        // The refusal's only observable effect. A listener that will never fire
        // is the one thing here that must not be dropped in silence, and without
        // this the WARN could be deleted with the suite still green.
        assertTrue(
            loop.warnings.any { it.contains("refusing fd=$FD") },
            "a refused listener must be reported, not dropped: ${loop.warnings}",
        )
    }

    @Test
    fun `registerIf after the sweep declines like a caller that stopped wanting it`() = loopTest { loop ->
        loop.failRemainingWaiters()

        val declined = CompletableDeferred<AbstractPosixReadinessEventLoop.Registration?>()
        launch {
            suspendCancellableCoroutine { cont ->
                declined.complete(loop.registerIf(FD, Interest.READ, cont) { true })
                cont.resumeWith(Result.success(Unit))
            }
        }

        // Null is the shape this path already has for "not appended", and its
        // caller already resumes the continuation itself -- so a closed ledger
        // needs no second failure mode here.
        assertNull(declined.await(), "a closed ledger declines the same way a withdrawn caller does")
        assertFalse(loop.waiters(FD, Interest.READ))
        assertTrue(loop.armed.isEmpty())
    }

    @Test
    fun `a listener that re-registers from onLoopStopped leaves the ledger empty`() {
        // The fixed point, end to end: this is the path that made the sweep not
        // one. The listener is told, tries to come back, and the ledger it
        // reaches is already closed -- so the sweep's postcondition survives the
        // sweep's own notifications.
        val loop = FakeLoop()
        val listener = RegisteringOnStopListener(loop)
        loop.addParticipant(listener)
        loop.registerCallback(FD, Interest.READ, listener)

        loop.failRemainingWaiters()

        // Both halves, or this passes for the wrong reason: a sweep that
        // stopped telling listeners at all would leave the ledger empty too.
        assertEquals(1, listener.toldCount, "the listener is told exactly once")
        assertFalse(
            loop.hasCallbackRegistration(FD, Interest.WRITE),
            "and its re-registration must not survive in a ledger the loop will never read",
        )
    }

    @Test
    fun `an off-loop caller goes through dispatch and an on-loop caller does not`() = loopTest { loop ->
        // This fake runs dispatched work immediately, so it distinguishes which
        // branch was taken, not when the arm happened. The queuing itself is
        // what the deferred-dispatch test below exercises.
        loop.onLoopThread = false
        suspendOn(loop, FD, Interest.READ).await()

        assertEquals(1, loop.dispatchCount, "an off-loop registration goes through dispatch")
        assertEquals(listOf(FD to Interest.READ), loop.armed)

        loop.onLoopThread = true
        suspendOn(loop, FD, Interest.WRITE).await()
        assertEquals(1, loop.dispatchCount, "an on-loop caller arms inline")
    }

    @Test
    fun `an off-loop arm does not reach the kernel until the loop drains it`() = loopTestWith(
        FakeLoop(onLoopThread = false, runDispatchedInline = false),
    ) { loop ->
        // The queuing the branch above only implies: nothing is armed while the
        // task sits in the queue, and the arm happens when the loop drains it.
        val w = suspendOn(loop, FD, Interest.READ).await()
        assertEquals(1, loop.dispatchCount)
        assertTrue(loop.armed.isEmpty(), "the arm waits for the loop")

        loop.drainDispatched()

        assertEquals(listOf(FD to Interest.READ), loop.armed, "and happens when the loop runs it")
        assertSame(w.reg, loop.popOne(FD, Interest.READ).first)
    }

    @Test
    fun `a failed arm drops the registration and fails its waiter`() = loopTest { loop ->
        loop.failArm = ENOMEM
        val w = suspendOn(loop, FD, Interest.READ).await()

        val failure = assertFailsWith<IllegalStateException> { w.resumed.await() }
        assertTrue(
            failure.message?.contains("errno=$ENOMEM") == true,
            "the errno reaches the waiter: ${failure.message}",
        )
        assertFalse(loop.waiters(FD, Interest.READ), "a failed arm must not leave the waiter in the chain")
    }

    @Test
    fun `an arm that lands after cancelAll neither arms nor resumes again`() = loopTestWith(
        FakeLoop(onLoopThread = false, runDispatchedInline = false),
    ) { loop ->
        // The window both engines guard: the append releases the lock, a close
        // runs cancelAll and resumes the waiter, and only then does the queued
        // arm run. Arming here would register an fd that may already be closed
        // — and resuming again would resume a continuation that is done.
        val w = suspendOn(loop, FD, Interest.READ).await()
        assertTrue(loop.armed.isEmpty(), "the arm is still queued")

        loop.cancelAll(FD, Interest.READ, IllegalStateException("server closed"))
        assertFailsWith<IllegalStateException> { w.resumed.await() }

        loop.drainDispatched()

        assertTrue(loop.armed.isEmpty(), "a waiter that left the chain must not be armed")
        assertFalse(loop.waiters(FD, Interest.READ))
    }

    @Test
    fun `the loop releases its thread id when it exits`() {
        // A pthread_t is unique only among live threads. Holding it past the
        // thread's lifetime lets an unrelated thread that inherits the id
        // answer inEventLoop with true, and act directly on state only the loop
        // may touch -- long after there is any loop to be on.
        val loop = RealQueueLoop()
        loop.loop()

        assertNull(loop.recordedLoopThread, "the id must not outlive the thread that owned it")
    }

    @Test
    fun `the registration lock stays usable after the loop has run and stopped`() {
        // Never destroyed, never freed, and that is load-bearing rather than
        // laziness: unregister runs from a cancellation handler on whichever
        // thread cancels, and a refused registration on a stopped loop cancels
        // its caller -- so acquisitions arrive after teardown, without bound.
        // Destroying the slot turns those into EINVAL; freeing it turns them
        // into a use-after-free. This pins that neither happens.
        val loop = RealQueueLoop()
        loop.loop() // full lifecycle: final drain, sweep, quiescence
        loop.takeRegLock()
        assertFalse(loop.lockBroken(), "a post-teardown acquisition must succeed, not report a failure")
        assertTrue(loop.lockFree(), "and must leave the lock free for the next one")
    }

    @Test
    fun `a registration-lock failure is reported and stops the loop`() {
        // The return values used to be discarded, so a failed acquire ran the
        // block with no exclusion at all and said nothing. A live pthread mutex
        // cannot be made to fail on demand, so the handler is driven directly;
        // what wires it to the acquire is covered by the mutation recorded with
        // this change.
        val loop = RealQueueLoop()
        loop.reportLockFailure("lock", EINVAL, stillHeld = false)

        assertTrue(loop.lockBroken(), "the loop must be told to stop")
        assertTrue(
            loop.logged.any { it.first == LogLevel.ERROR && "registration lock" in it.second },
            "and the failure must be reported, not swallowed: ${loop.logged}",
        )
        assertEquals(1, loop.wakeups, "the loop is woken so it notices without waiting for an event")
    }

    @Test
    fun `the sweep is skipped when a failed release left the lock held`() = loopTest { loop ->
        // Re-taking a mutex this thread already holds deadlocks it, so the
        // sweep steps aside -- but it must still close the ledgers, or every
        // later registration appends to a loop that will never arm it and
        // parks forever, which is the hang the sweep exists to end.
        suspendOn(loop, FD, Interest.READ).await()
        loop.reportLockFailure("unlock", EINVAL, stillHeld = true)

        loop.failRemainingWaiters()

        assertTrue(loop.waiters(FD, Interest.READ), "the waiter is left parked, as the log says")
        assertTrue(
            loop.errors.any { "skipping the stop sweep" in it },
            "and the skip is reported: ${loop.errors}",
        )
        // The ledgers must still be closed, or a later registration appends to a
        // loop that will never arm it. Probed the way the other refusal tests
        // do it: a callback that lands in the ledger is one that was accepted.
        loop.registerCallback(FD, Interest.WRITE, RecordingListener())
        assertFalse(
            loop.hasCallbackRegistration(FD, Interest.WRITE),
            "the ledgers must still be closed, or later registrations park on a dead loop",
        )
    }

    @Test
    fun `the sweep still runs when only the acquire failed`() = loopTest { loop ->
        // A failed acquire leaves nothing held, so there is no deadlock to
        // avoid -- ending the waiters unguarded beats leaving them parked.
        suspendOn(loop, FD, Interest.READ).await()
        loop.reportLockFailure("lock", EINVAL, stillHeld = false)

        loop.failRemainingWaiters()

        assertFalse(loop.waiters(FD, Interest.READ), "the waiter is ended, not skipped")
    }

    @Test
    fun `a registration-lock failure on a quiescent loop does not write the wakeup fd`() {
        // The failure path runs on whichever thread took the lock, which after
        // teardown is any thread at all -- and by then the loop's own close has
        // released the wakeup fd, whose number the kernel may have re-handed.
        // Same guard, and the same reason, as the dispatch path.
        val loop = RealQueueLoop()
        loop.loop() // publishes quiescence
        val before = loop.wakeups

        loop.reportLockFailure("lock", EINVAL, stillHeld = false)

        assertTrue(loop.lockBroken(), "the failure is still recorded")
        assertEquals(before, loop.wakeups, "but a quiescent loop must not be woken")
    }

    @Test
    fun `registerIf arms through the funnel when the caller is off the loop`() = loopTest { loop ->
        // registerIf is the accept() arming path; register's off-loop route is
        // covered separately, and a regression in this one would not show there.
        loop.onLoopThread = false
        val accepted = CompletableDeferred<AbstractPosixReadinessEventLoop.Registration?>()
        launch {
            suspendCancellableCoroutine { cont ->
                accepted.complete(loop.registerIf(FD, Interest.READ, cont) { true })
            }
        }
        val reg = accepted.await()

        assertEquals(1, loop.dispatchCount, "an off-loop registerIf goes through dispatch")
        assertEquals(listOf(FD to Interest.READ), loop.armed)
        assertSame(reg, loop.popOne(FD, Interest.READ).first)
    }

    @Test
    fun `the sweep fails every waiter the loop will never arm`() = loopTest { loop ->
        // What the loop runs after its final drain. Anything still in the ledger
        // is waiting on an arm that can no longer issue, so it has to end here --
        // on the loop thread, where the lock is valid as it is everywhere,
        // because nothing ever frees it.
        val readers = (0..1).map { suspendOn(loop, FD, Interest.READ).await() }
        val writer = suspendOn(loop, FD, Interest.WRITE).await()

        loop.failRemainingWaiters()

        for (w in readers + writer) {
            assertSweptFailure(w.resumed)
        }
        assertFalse(loop.waiters(FD, Interest.READ), "the ledger is emptied")
        assertFalse(loop.waiters(FD, Interest.WRITE))
    }

    @Test
    fun `the sweep runs each waiter's cancellation handler`() = loopTest { loop ->
        // The reason it cancels rather than resuming with the failure: only a
        // cancelled continuation runs invokeOnCancellation, and that handler is
        // what closes the socket on the connect path. Measured, not assumed.
        var handlerRan = false
        val done = CompletableDeferred<Unit>()
        launch {
            try {
                suspendCancellableCoroutine { cont ->
                    val reg = loop.registerWaiter(FD, Interest.WRITE, cont)
                    cont.invokeOnCancellation {
                        handlerRan = true
                        loop.unregister(reg)
                    }
                }
                done.complete(Unit)
            } catch (t: Throwable) {
                done.completeExceptionally(t)
            }
        }
        while (!loop.waiters(FD, Interest.WRITE)) yield()

        loop.failRemainingWaiters()

        assertSweptFailure(done)
        assertTrue(handlerRan, "the handler that releases the fd must run")
    }

    @Test
    fun `the sweep does not cancel the waiter's parent`() = runBlocking {
        withTimeout(TEST_BUDGET) {
            // The reason the cause is a CancellationException at all: any other
            // cause completes the waiter's coroutine exceptionally and cancels
            // its parent. The other sweep tests catch the throwable inside the
            // waiter, so their launch completes normally and none of them can
            // see this -- here it escapes, and what is asserted is that the
            // scope around it survives.
            //
            // The handler is what makes this test able to fail *readably*.
            // Measured: with the cause mutated to a plain IllegalStateException
            // and no handler installed, the waiter's failure reaches Kotlin's
            // uncaught-exception path and takes the test process down before any
            // assertion runs — reported only as "Unknown". Nor does
            // `waiter.isCancelled` discriminate: a Job that completed
            // exceptionally reports that too. What separates the two cases is
            // whether anything reached this handler, and whether the scope lived.
            val loop = FakeLoop()
            val reported = mutableListOf<Throwable>()
            val parent = CoroutineScope(
                coroutineContext + Job() + CoroutineExceptionHandler { _, t -> reported.add(t) },
            )
            try {
                // Written out rather than via suspendOn, which launches its own
                // coroutine and catches the throwable — that is exactly what
                // hides the property under test.
                val waiter = parent.launch {
                    suspendCancellableCoroutine { cont ->
                        val reg = loop.registerWaiter(FD, Interest.READ, cont)
                        cont.invokeOnCancellation { loop.unregister(reg) }
                    }
                }
                while (!loop.waiters(FD, Interest.READ)) yield()

                loop.failRemainingWaiters()
                waiter.join()

                assertTrue(waiter.isCancelled, "the waiter itself ends as cancelled")
                assertTrue(reported.isEmpty(), "the loop stopping is not a failure to report: $reported")
                assertTrue(parent.isActive, "its parent must survive the loop stopping under it")
            } finally {
                parent.cancel()
                withContext(NonCancellable) { parent.coroutineContext.job.join() }
            }
            // This test hand-rolls its scope, and is the only sweep test whose
            // waiter is not wrapped in a catch -- the shape most likely to leave
            // a cancellation handler mid-flight, which is exactly the caller
            // that must still find a working lock.
            assertFalse(loop.lockBroken(), "the cancellation handlers must not have broken the lock")
            assertTrue(loop.lockFree(), "nor left it held")
        }
    }
}
