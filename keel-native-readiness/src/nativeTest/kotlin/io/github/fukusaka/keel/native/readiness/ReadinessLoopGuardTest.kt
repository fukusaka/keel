package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the two guards the base keeps on its own queue and loop.
 */
@OptIn(InternalReadinessEngineApi::class)
internal class ReadinessLoopGuardTest : AbstractReadinessEventLoopFixture() {

    // --- the two guards on the base's own queue and loop ---

    @Test
    fun `a task that drains again does not lose the batch it re-entered`() {
        // drainTasks is re-entrant from a task it is running. The outer call
        // already drains until the queue is empty, so the inner one has nothing
        // left to do -- and must not clear the shared batch under the iteration.
        val loop = RealQueueLoop()
        val ran = mutableListOf<String>()
        loop.dispatch(EmptyCoroutineContext, Runnable { ran.add("first") })
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                ran.add("re-enters")
                loop.drain()
            },
        )
        loop.dispatch(EmptyCoroutineContext, Runnable { ran.add("third") })

        loop.drain()

        assertEquals(listOf("first", "re-enters", "third"), ran, "every task runs exactly once")
    }

    @Test
    fun `an off-loop registerCallback goes through the real queue and wakes the loop`() {
        // [FakeLoop] answers every pipeline test, and it replaces both `dispatch`
        // and `drainTasks` with a list -- so none of them reaches MpscQueue,
        // drainQueue's batch loop, or the `if (!inEventLoop() &&
        // !handoff.isQuiescent())` wakeup branch an off-loop registration on a
        // live loop actually takes. A regression that queued the arm and
        // skipped the wakeup would leave the re-arm waiting for an unrelated
        // event and pass every one of them.
        val loop = RealQueueLoop(onLoopThread = false)
        loop.registerCallback(FD, Interest.READ, RecordingListener())

        assertTrue(loop.armedCallbacks.isEmpty(), "the arm is queued, not run on the caller")
        assertEquals(1, loop.wakeups, "and an off-loop caller wakes the loop")

        loop.onLoopThread = true
        loop.drain()

        assertEquals(listOf(FD to Interest.READ), loop.armedCallbacks, "the real drain delivers it")
    }

    @Test
    fun `a task that throws does not stop the rest of its batch`() {
        val loop = RealQueueLoop()
        var laterRan = false
        loop.dispatch(EmptyCoroutineContext, Runnable { throw IllegalStateException("boom") })
        loop.dispatch(EmptyCoroutineContext, Runnable { laterRan = true })

        loop.drain()

        assertTrue(laterRan, "the task queued after the throwing one must still run")
        assertTrue(loop.logged.any { it.first == LogLevel.WARN }, "the throw must be reported: ${loop.logged}")
    }

    @Test
    fun `entering the loop a second time is refused without throwing`() {
        // loop() runs as a pthread entry point with nothing above it to catch,
        // so an entry that does not get the termination claim is reported and
        // ignored rather than thrown -- and it must not re-point the thread
        // identity the whole class reads. A second `loop()` is one way to
        // arrive without the claim; a `close()` that ran the teardown first is
        // the other.
        val loop = RealQueueLoop()
        loop.loop()
        val errorsAfterFirst = loop.logged.count { it.first == LogLevel.ERROR }

        loop.loop()

        assertEquals(0, errorsAfterFirst, "the first entry is not an error")
        assertTrue(
            loop.logged.any { it.first == LogLevel.ERROR && it.second.contains("already claimed") },
            "the second entry must be reported: ${loop.logged}",
        )
        // The log line alone would pass for a guard that reports and then
        // falls through, re-publishing the thread and running the whole
        // teardown a second time on a live loop. The drain count is what
        // says the second entry returned: one completed loop() drains
        // twice -- the final drain, then the sweep's unconditional one --
        // so a fallen-through second entry would double it to four.
        assertEquals(2, loop.drainCalls, "teardown ran once, not twice")
        assertFalse(loop.lockBroken(), "and left the registration lock working")
    }

    @Test
    fun `dispatch wakes the loop only when the caller is off it`() {
        // The branch is one line and its failure mode is a stall, not a log:
        // a cross-thread dispatch that skips the wakeup leaves the task queued
        // while the kernel wait sits on its deadline, or forever. Neither of
        // this file's other subclasses can reach it -- one answers on-loop
        // unconditionally, the other overrides dispatch entirely.
        val loop = RealQueueLoop(onLoopThread = true)
        loop.dispatch(EmptyCoroutineContext, Runnable { })
        assertEquals(0, loop.wakeups, "an on-loop caller drains before the next wait")

        loop.onLoopThread = false
        loop.dispatch(EmptyCoroutineContext, Runnable { })
        assertEquals(1, loop.wakeups, "an off-loop caller has to interrupt the wait")
    }

    @Test
    fun `dispatch to a quiescent loop keeps the offer but skips the wakeup`() {
        // Once the loop published quiescence its close may already have
        // released the wakeup fd -- and the kernel may have re-handed the
        // number -- so the write would land in someone else's descriptor. The
        // offer stays: bounded retention on a queue nothing reads, which is
        // the best a dispatch to a dead loop can do.
        val loop = RealQueueLoop()
        loop.loop() // runs to completion: finished, swept, quiescent
        loop.onLoopThread = false
        var ran = false
        loop.dispatch(EmptyCoroutineContext, Runnable { ran = true })
        assertEquals(0, loop.wakeups, "a quiescent loop must not be woken")
        assertFalse(ran, "and nothing runs the task -- the queue is dead")
        assertTrue(loop.queueHoldsWork(), "the offer stays: dropping it is not what this guard does")
    }

    // --- what the backstop withdraws when a listener throws ---

    @Test
    fun `a listener that throws loses the registration it put back itself`() {
        // The re-arm a failing listener makes on its way out is not evidence
        // that it can be called again: the readiness that woke it is still
        // there, so honouring it hands the same event to the same throw on the
        // next turn, and the turn after.
        val loop = FakeLoop()
        val thrower = object : FdReadyListener {
            override fun onReady(interest: Interest) {
                loop.registerCallback(FD, interest, this)
                throw IllegalStateException("armed, then failed")
            }
        }
        loop.registerCallback(FD, Interest.READ, thrower)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertFalse(
            loop.hasCallbackRegistration(FD, Interest.READ),
            "its own re-arm does not survive the throw",
        )
        assertEquals(listOf(FD to Interest.READ), loop.disarmed, "and the interest goes back")
    }

    @Test
    fun `a listener that throws does not take a registration another party made`() {
        // Withdrawing by key alone would. A listener that ends its connection
        // closes the fd on the way through the call, so the number is free from
        // that moment: a connect on another thread can be handed it back and
        // register on this very key before the throw is dealt with. Dropping
        // that leaves a channel that reports itself open, never reads a byte
        // and never learns of a close -- and, with the interest taken back too,
        // nothing to revive it.
        val loop = FakeLoop()
        val newcomer = object : FdReadyListener {
            override fun onReady(interest: Interest) = Unit
        }
        val thrower = object : FdReadyListener {
            override fun onReady(interest: Interest) {
                loop.registerCallback(FD, interest, newcomer)
                throw IllegalStateException("ended this connection, and failed doing it")
            }
        }
        loop.registerCallback(FD, Interest.READ, thrower)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertTrue(
            loop.hasCallbackRegistration(FD, Interest.READ),
            "the party that now owns the key keeps its registration",
        )
        assertEquals(emptyList(), loop.disarmed, "and keeps the interest armed with it")
    }

    @Test
    fun `a loop that never ran is taken apart by whoever closes it`() {
        // Nothing published `finished` or `quiescent` for such a loop, so the
        // hand-off reads it as live and offers work no drain would ever run.
        // The closing thread runs the loop's terminal sequence instead.
        val loop = RealQueueLoop(onLoopThread = false)
        var ran = 0
        loop.runOnLoop(onLoop = { ran++ }, ifStopped = { })

        assertEquals(0, ran, "premise: nothing runs it -- there is no thread")
        assertEquals(0, loop.drainCalls, "premise: no drain has happened")

        assertTrue(loop.finishWithoutRunning(), "an unclaimed loop is this caller's to take apart")

        assertEquals(1, ran, "the queued work runs, in the terminal sequence's drain")
        // Two drains per completed sequence -- the final one, then the
        // sweep's unconditional one. The same count the double-entry guard
        // uses, and what says the sequence ran exactly once.
        assertEquals(2, loop.drainCalls, "the sequence ran once, not twice")
        assertTrue(loop.isStopped(), "and published quiescence, so nothing waits on it")
        assertNull(loop.recordedLoopThread, "the claiming thread's identity is cleared on the way out")
    }

    @Test
    fun `only one thread takes a loop apart and it is whichever gets there first`() {
        // The claim is what makes the confinement a fact: the sequence walks
        // ledgers, and two walkers would do it against each other.
        val closed = RealQueueLoop(onLoopThread = false)
        assertTrue(closed.finishWithoutRunning(), "the first caller takes it")

        assertFalse(closed.finishWithoutRunning(), "a second caller is refused")
        assertEquals(2, closed.drainCalls, "and does not run the sequence again")

        closed.loop()
        assertEquals(2, closed.drainCalls, "nor does a `loop()` arriving afterwards")
        assertTrue(
            closed.logged.any { it.first == LogLevel.ERROR && it.second.contains("already claimed") },
            "which is reported: ${closed.logged}",
        )

        // And the other way round: a loop that ran owns its own end.
        val ran = RealQueueLoop()
        ran.loop()
        assertEquals(2, ran.drainCalls, "premise: the loop ran its sequence")

        assertFalse(ran.finishWithoutRunning(), "a closer arriving after the loop is told to join instead")
        assertEquals(2, ran.drainCalls, "and runs nothing")
    }

    // --- the loop's own hand-off to a waiter ---

    @Test
    fun `a waiter whose resume throws does not take the loop with it`() {
        val loop = FakeLoop()
        val refusing = RefusingDispatcher()
        // A scope of its own, not this test's: the waiter's dispatcher refuses
        // the resumption, so that coroutine can never complete, and a child of
        // the test would hold the test open forever. UNDISPATCHED so the launch
        // itself needs no dispatcher — the body runs inline to its suspension
        // point, and only the resume below asks the dispatcher to take it back.
        CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
                loop.registerWaiter(WAITER_FD, Interest.READ, cont)
            }
        }
        assertTrue(loop.waiters(WAITER_FD, Interest.READ), "the waiter must be registered")

        // The dispatcher refuses to take the resumed continuation back, so the
        // resume throws in the loop's own frame. Readiness dispatch is per-event
        // work of the loop body, and the pthread entry above it catches nothing:
        // an escape here ends the process, taking every other connection on this
        // engine with the one that failed. Its sibling — a listener that throws
        // — has been guarded since it was written.
        loop.dispatchReadyFor(WAITER_FD, Interest.READ, eofFlag = false)

        assertEquals(1, refusing.attempts, "the seam must have reached the resume")
        assertTrue(
            loop.errors.any { it.contains("the loop goes on serving") },
            "the report must name what carries on here, got: ${loop.errors}",
        )
        // The waiter stays out of the ledger. Putting it back -- the plausible
        // "it was never resumed, so let the next event retry" repair -- makes a
        // level-triggered interest re-fire into `Already resumed` every turn,
        // and the continuation may well hold the answer already.
        assertFalse(loop.waiters(WAITER_FD, Interest.READ), "the waiter must not be re-registered")
        assertEquals(
            listOf(WAITER_FD to Interest.READ),
            loop.disarmed,
            "and the interest it was the last claimant of is taken back",
        )
        // What the guard is for: the loop is still serving. A listener armed
        // after the refusal receives its readiness.
        val served = mutableListOf<Interest>()
        loop.registerCallbackFor(WAITER_FD, Interest.READ, ReadyRecorder(served))
        loop.dispatchReadyFor(WAITER_FD, Interest.READ, eofFlag = false)
        assertEquals(listOf(Interest.READ), served, "the loop goes on serving the rest")
    }

    @Test
    fun `a waiter that owns a descriptor releases it when the loop cannot deliver`() {
        val loop = FakeLoop()
        val refusing = RefusingDispatcher()
        var released = 0
        CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
                loop.registerOwningWaiter(WAITER_FD, Interest.WRITE, cont) { released++ }
            }
        }

        // Neither of the waiter's own endings can run: it is not resumed into
        // its own frame and not cancelled, and it has already left the ledger,
        // so no later sweep finds it. The hook is the only thing that knows the
        // descriptor is owned — without it the connect path leaks one fd per
        // occurrence, with no handle left to close it by.
        loop.dispatchReadyFor(WAITER_FD, Interest.WRITE, eofFlag = false)

        assertEquals(1, refusing.attempts, "the seam must have reached the resume")
        assertEquals(1, released, "the undeliverable waiter's owned descriptor must be released")
    }

    @Test
    fun `a refusing waiter does not strand the ones queued behind it when a server closes`() {
        val loop = FakeLoop()
        val refusing = RefusingDispatcher()
        // Head of the FIFO chain: registered first, so cancelAll reaches it
        // first and its dispatcher refuses.
        CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
                loop.registerWaiter(WAITER_FD, Interest.READ, cont)
            }
        }
        // Behind it, a sibling accept() caller whose dispatcher is fine. Both
        // leave the ledger together in cancelAll's drain, into a local list
        // nothing else can reach — so if the refusal aborts the loop, this one
        // waits forever on a server that is already closing.
        var sibling: Throwable? = null
        CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                suspendCancellableCoroutine { cont ->
                    loop.registerWaiter(WAITER_FD, Interest.READ, cont)
                }
            }.onFailure { sibling = it }
        }

        loop.cancelAll(WAITER_FD, Interest.READ, InjectedFault("server closing"))

        assertEquals(1, refusing.attempts, "the seam must have reached the refusing waiter")
        // By message: the type alone says less than it looks like it does.
        // The refusal the dispatcher throws is a DispatchException wrapping an
        // InjectedFault, so a type-only assertion does reject it -- but it
        // equally accepts any other InjectedFault, and the cause this close
        // hands out is the one thing worth naming.
        assertEquals(
            "server closing",
            (sibling as? InjectedFault)?.message,
            "the sibling must be failed with the close's own cause, got: $sibling",
        )
        assertTrue(
            loop.errors.any { it.contains("while the server closes") },
            "the report must name what carries on here, got: ${loop.errors}",
        )
    }

    @Test
    fun `a refusing waiter does not strand the ones the stop sweep still has to end`() {
        val loop = FakeLoop()
        val refusing = RefusingDispatcher()
        // Armed, but no readiness is ever dispatched for them, so the sweep is
        // what ends them. Head of the chain refuses; the sibling behind it must
        // still be cancelled.
        CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
                loop.registerWaiter(WAITER_FD, Interest.READ, cont)
            }
        }
        var sibling: Throwable? = null
        CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                suspendCancellableCoroutine { cont ->
                    loop.registerWaiter(WAITER_FD, Interest.READ, cont)
                }
            }.onFailure { sibling = it }
        }

        loop.failRemainingWaiters()

        assertEquals(1, refusing.attempts, "the seam must have reached the refusing waiter")
        // By message: on this target `CancellationException` is an
        // `IllegalStateException`, so the type proves nothing — the fixture's
        // own helper documents that from measurement. The sweep's own wording
        // is what says this came from the sweep.
        assertTrue(
            sibling?.message?.contains(SWEEP_FAILURE) == true,
            "the sibling behind the refusal must still be ended by the sweep, got: $sibling",
        )
        assertTrue(
            loop.errors.any { it.contains("while the loop stops") },
            "the report must name what carries on here, got: ${loop.errors}",
        )
    }

    @Test
    fun `a waiter that owns a descriptor releases it when its arm could not be made`() {
        val loop = FakeLoop(onLoopThread = false, runDispatchedInline = false)
        loop.failArm = ENOMEM
        val refusing = RefusingDispatcher()
        var released = 0
        CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCancellableCoroutine { cont ->
                loop.registerOwningWaiter(WAITER_FD, Interest.WRITE, cont) { released++ }
            }
        }

        // Off-loop caller, so the arm is queued rather than run inline — the
        // shape connect() has. When the loop runs it and the arm fails, the
        // waiter is resumed with that failure, through its own dispatcher,
        // which refuses. Neither of the wait's own endings can run then: the
        // continuation is completed exceptionally in state, so the
        // cancellation handler will not fire, and the coroutine never resumes,
        // so its catch does not either.
        loop.drainDispatched()

        assertEquals(1, refusing.attempts, "the seam must have reached the resume")
        assertEquals(1, released, "the arm failure must release what the waiter owned")
        // Like its three siblings: the report names the delivery and what
        // carries on without it, which is the contract the helper's KDoc states
        // and the only part of the report a reader acts on.
        assertTrue(
            loop.errors.any { it.contains("while the loop goes on arming others") },
            "the report must name what carries on here, got: ${loop.errors}",
        )
    }

    /** Records the interests it is handed, for the served-after-failure assertion. */
    private class ReadyRecorder(private val into: MutableList<Interest>) : FdReadyListener {
        override fun onReady(interest: Interest) {
            into.add(interest)
        }
    }

    /**
     * Refuses to take a resumed continuation back, the way a dispatcher backed
     * by a shut-down executor does — the reachable shape of a resume that
     * throws in the loop's frame.
     */
    private class RefusingDispatcher : CoroutineDispatcher() {
        var attempts: Int = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            attempts++
            throw InjectedFault("dispatcher refused the resumed continuation")
        }
    }

    private companion object {
        /** Arbitrary fd for the waiter registration; no syscall reaches it. */
        const val WAITER_FD = 41
    }
}
