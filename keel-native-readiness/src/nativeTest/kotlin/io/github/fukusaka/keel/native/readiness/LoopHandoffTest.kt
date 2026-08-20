package io.github.fukusaka.keel.native.readiness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Unit tests for [LoopHandoff] — the off-loop to EventLoop hand-off the POSIX
 * readiness engines share.
 *
 * These need no real loop: the hand-off takes its two loop dependencies as
 * lambdas, so a test drives the queue by hand and decides when the loop is on
 * the caller's thread, when it has finished polling, and when its final drain
 * is complete. That is coverage the engines could only reach through a live
 * kqueue / epoll before this was extracted.
 *
 * Three of these never block: they take the inline path, return at the
 * `loopFinished` guard, or find the loop already quiescent so the spin never
 * iterates. The four that do enter it carry a wall-clock bound, because a
 * regression that never publishes quiescence would otherwise hang rather than
 * fail. One drives the spin from a second thread; the rest deliberately do not
 * join what they launch, and say why where they do it.
 */
@OptIn(InternalReadinessEngineApi::class, ExperimentalForeignApi::class)
class LoopHandoffTest {

    /**
     * Records dispatched tasks so a test can run them when it chooses.
     *
     * The queue is an immutable list swapped under CAS rather than a
     * `MutableList`: the tests that drive the wait offer from one thread and
     * observe the offer from another, and an unsynchronised list gives the
     * reader no ordering edge to see it through — the same reason the engines'
     * recording loggers are built this way.
     */
    private class FakeLoop {
        private val tasks = AtomicReference<List<() -> Unit>>(emptyList())
        var onLoopThread = false

        val queue: List<() -> Unit> get() = tasks.value

        fun handoff(): LoopHandoff = LoopHandoff(
            inEventLoop = { onLoopThread },
            dispatchToLoop = { task ->
                while (true) {
                    val current = tasks.value
                    if (tasks.compareAndSet(current, current + task)) break
                }
            },
        )

        /** Runs everything queued, as the loop's drain would. */
        fun drain() {
            val pending = tasks.value
            tasks.value = emptyList()
            pending.forEach { it() }
        }
    }

    @Test
    fun `the first reason a loop ended is the one it keeps`() {
        // A loop ends once. What follows is the terminal sequence reacting to
        // that -- a sweep whose participant throws, a drain that fails -- and
        // a waiter told one of those would be told the consequence instead of
        // the cause.
        val loop = FakeLoop()
        val handoff = loop.handoff()
        val why = IllegalStateException("the loop body threw")

        assertNull(handoff.loopFailure(), "a loop that has not failed has nothing to report")

        handoff.recordLoopFailure(why)
        handoff.recordLoopFailure(IllegalStateException("and then the wind-down did too"))

        assertSame(why, handoff.loopFailure(), "the reason it ended must survive what happened next")
    }

    @Test
    fun `on the loop thread the work runs inline and nothing is queued`() {
        val loop = FakeLoop().apply { onLoopThread = true }
        val handoff = loop.handoff()
        var onLoop = 0
        var ifStopped = 0

        val outcome = handoff.runOnLoop(onLoop = { onLoop++ }, ifStopped = { ifStopped++ })

        assertEquals(HandoffOutcome.HANDED_TO_LOOP, outcome, "the loop ran it, so the caller reports nothing")
        assertEquals(1, onLoop, "the caller already owns the loop, so it runs the work itself")
        assertEquals(0, ifStopped)
        assertTrue(loop.queue.isEmpty(), "nothing may be queued when it ran inline")
    }

    @Test
    fun `off the loop thread the work is queued for the loop rather than run by the caller`() {
        val loop = FakeLoop()
        val handoff = loop.handoff()
        var onLoop = 0
        var ifStopped = 0

        val outcome = handoff.runOnLoop(onLoop = { onLoop++ }, ifStopped = { ifStopped++ })

        assertEquals(HandoffOutcome.HANDED_TO_LOOP, outcome, "the loop has it, even though it has not run yet")
        assertEquals(0, onLoop, "the caller must not run loop-owned work itself")
        assertEquals(1, loop.queue.size)

        loop.drain()
        assertEquals(1, onLoop)
        assertEquals(0, ifStopped)
    }

    @Test
    fun `a caller that arrives after the loop is quiet runs the fallback itself`() {
        val loop = FakeLoop()
        val handoff = loop.handoff()
        handoff.markFinished()
        handoff.markQuiescent()
        var onLoop = 0
        var ifStopped = 0

        val outcome = handoff.runOnLoop(onLoop = { onLoop++ }, ifStopped = { ifStopped++ })

        assertEquals(HandoffOutcome.FELL_BACK, outcome, "the fallback ran, and no wait was cut short to get there")
        assertEquals(1, ifStopped, "the loop is gone, so the caller must do the fallback")
        assertEquals(0, onLoop)

        // Nothing was queued: a quiescent loop is not dispatched to at all —
        // the offer would pin the closure in a queue nothing reads. A drain
        // (there will not be one) finds nothing to run.
        assertTrue(loop.queue.isEmpty(), "no task may be offered to a quiescent loop")
        loop.drain()
        assertEquals(0, onLoop, "nothing was queued, so nothing can run")
    }

    @Test
    fun `a caller waits out the final drain before taking the fallback`() = runBlocking {
        // The state the two flags exist to distinguish: the loop has stopped
        // polling (finished) but has not finished draining (not yet quiescent).
        // A caller must wait here — acting on `finished` alone would release an
        // fd the loop can still arm from a queued registration.
        //
        // Collapsing the flags into one would let this caller run its fallback
        // before the drain, which is what the assertions below rule out.
        val loop = FakeLoop()
        val handoff = loop.handoff()
        val drained = AtomicInt(0)
        val ranBeforeDrain = AtomicInt(0)

        handoff.markFinished()

        withTimeout(WAIT_BUDGET) {
            val waiter = launch(Dispatchers.Default) {
                handoff.runOnLoop(
                    onLoop = {},
                    ifStopped = { if (drained.value == 0) ranBeforeDrain.value = 1 },
                )
            }
            // Let the waiter reach the spin, then complete the drain the way the
            // loop does. Publishing quiescence is what releases it.
            launch(Dispatchers.Default) {
                while (loop.queue.isEmpty()) { /* wait for the offer */ }
                drained.value = 1
                handoff.markQuiescent()
            }
            waiter.join()
        }

        assertEquals(0, ranBeforeDrain.value, "the fallback must not run before the drain completes")
    }

    @Test
    fun `a caller that hands in a budget stops waiting for a loop that never goes quiet`() {
        // The default wait ends only when the loop publishes quiescence, which
        // is right for a caller that blocks its own closing thread and wrong for
        // one whose thread other work depends on -- the accept hand-off runs on
        // a boss EventLoop, and waiting there stops every listener that loop
        // serves and everything queued for it. A budget trades the ordering the
        // wait provides for a bound on how long anything can be held.
        //
        // Quiescence is never published here: the whole point is that the caller
        // returns anyway.
        val loop = FakeLoop()
        val handoff = loop.handoff()
        val onLoop = AtomicInt(0)
        val ifStopped = AtomicInt(0)
        val gaveUp = AtomicInt(0)
        val finished = AtomicInt(0)
        handoff.markFinished()

        // Deliberately launched outside this test's scope, and polled rather
        // than joined. If the budget ever stops being honoured, this waiter
        // sits in `usleep` where no coroutine cancellation reaches it -- and a
        // `runBlocking` that owns it would then hang the whole suite instead of
        // failing this one test (the suite runs in a single process, so that is
        // a hang with no output, not a red build).
        CoroutineScope(Dispatchers.Default).launch {
            val outcome = handoff.runOnLoop(
                onLoop = { onLoop.value = onLoop.value + 1 },
                ifStopped = { ifStopped.value = ifStopped.value + 1 },
                waitBudgetMicros = SHORT_BUDGET_MICROS,
            )
            if (outcome == HandoffOutcome.FELL_BACK_AFTER_EXPIRY) gaveUp.value = 1
            finished.value = 1
        }

        val deadline = TimeSource.Monotonic.markNow()
        while (finished.value == 0 && deadline.elapsedNow() < WAIT_BUDGET) {
            usleep(POLL_MICROS)
        }

        assertEquals(
            1,
            finished.value,
            "the budget must end the wait: nothing here can interrupt a spin that ignores it",
        )
        assertEquals(1, gaveUp.value, "the caller must say it released without the ordering the wait would have given")
        assertEquals(1, ifStopped.value, "giving up means running the fallback, not skipping it")

        // The offer is still in the queue, and the claim already went to the
        // fallback: a drain arriving late must find the work taken rather than
        // run it. Counting `onLoop` is what pins that -- asserting only that
        // `ifStopped` stayed at 1 would pass with the claim deleted, since the
        // drain runs the other block.
        assertEquals(0, onLoop.value, "premise: the queued work has not run yet")
        loop.drain()
        assertEquals(0, onLoop.value, "the claim is what makes the two exclusive, budget or no budget")
        assertEquals(1, ifStopped.value, "and the fallback is not re-run either")
    }

    @Test
    fun `a spent-out budget takes the fallback without waiting at all`() {
        // The value a caller reaches once its allowance is gone, and the one
        // the accept path hands in for every connection after that. It has to
        // mean "do not wait", not "wait forever": the guard is `budget >= 0`,
        // and a `> 0` there would turn every spent-out hand-off into the
        // unbounded wait the allowance exists to prevent -- worse than having
        // no allowance at all, and invisible to every other test here.
        val loop = FakeLoop()
        val handoff = loop.handoff()
        val ifStopped = AtomicInt(0)
        handoff.markFinished()

        // Detached and polled, like the budget tests below: nothing here
        // publishes quiescence, so if zero ever sleeps the call never returns
        // -- and running it on this thread would hang the suite rather than
        // fail this test. Measured (and asserted) as "came back promptly",
        // because "did not sleep" has no other observable.
        val gaveUp = AtomicInt(0)
        val finished = AtomicInt(0)
        CoroutineScope(Dispatchers.Default).launch {
            val outcome = handoff.runOnLoop(
                onLoop = {},
                ifStopped = { ifStopped.value = ifStopped.value + 1 },
                waitBudgetMicros = 0,
            )
            if (outcome == HandoffOutcome.FELL_BACK_AFTER_EXPIRY) gaveUp.value = 1
            finished.value = 1
        }

        val deadline = TimeSource.Monotonic.markNow()
        while (finished.value == 0 && deadline.elapsedNow() < NO_WAIT_CEILING) {
            usleep(POLL_MICROS)
        }

        assertEquals(
            1,
            finished.value,
            "zero must not sleep: the loop never goes quiet, so any sleep at all is an unbounded one",
        )
        assertEquals(1, gaveUp.value, "a zero allowance is spent before it is used")
        assertEquals(1, ifStopped.value, "and the fallback still runs")
    }

    @Test
    fun `a loop that goes quiet inside the budget is waited out rather than given up on`() {
        // The budget is a ceiling, not a delay: a teardown that finishes inside
        // it must still get the ordering, and the caller must not report having
        // gone without it.
        //
        // Detached and polled for the same reason as the test above, and the
        // signalling is done here rather than from a second coroutine: a
        // `while (queue.isEmpty())` spin in one would be as uncancellable as
        // the wait itself, and owning it would hand the suite the very hang
        // this shape exists to avoid.
        val loop = FakeLoop()
        val handoff = loop.handoff()
        val drained = AtomicInt(0)
        val ranBeforeDrain = AtomicInt(0)
        val gaveUp = AtomicInt(0)
        val finished = AtomicInt(0)
        handoff.markFinished()

        CoroutineScope(Dispatchers.Default).launch {
            val outcome = handoff.runOnLoop(
                onLoop = {},
                ifStopped = { if (drained.value == 0) ranBeforeDrain.value = 1 },
                waitBudgetMicros = GENEROUS_BUDGET_MICROS,
            )
            if (outcome == HandoffOutcome.FELL_BACK_AFTER_EXPIRY) gaveUp.value = 1
            finished.value = 1
        }

        val deadline = TimeSource.Monotonic.markNow()
        while (loop.queue.isEmpty() && deadline.elapsedNow() < WAIT_BUDGET) {
            usleep(POLL_MICROS)
        }
        assertEquals(1, loop.queue.size, "premise: the waiter offered its work")
        // The offer happens several statements before the spin is entered, so
        // publishing quiescence the moment it appears usually wins that race
        // and the waiter never sleeps at all -- the test would then pass
        // without exercising the wait it is named for. This does not make the
        // assertions depend on timing; it only stops them passing vacuously.
        usleep(SPIN_ENTRY_MICROS)
        drained.value = 1
        handoff.markQuiescent()

        while (finished.value == 0 && deadline.elapsedNow() < WAIT_BUDGET) {
            usleep(POLL_MICROS)
        }

        assertEquals(1, finished.value, "quiescence must release the wait well inside the budget")
        assertEquals(0, ranBeforeDrain.value, "the fallback must not run before the drain completes")
        assertEquals(0, gaveUp.value, "the wait ended on quiescence, so nothing was given up")
    }

    private companion object {
        /**
         * Wall-clock bound for the one test that blocks. The spin it waits on is
         * released by another thread within microseconds; this only exists so a
         * regression that never publishes quiescence fails instead of hanging.
         */
        val WAIT_BUDGET = 15.seconds

        /**
         * Short enough that the give-up test does not slow the suite, long
         * enough not to expire on a loaded runner before the waiter has even
         * reached the spin — which would pass for the wrong reason.
         */
        const val SHORT_BUDGET_MICROS = 20_000L

        /**
         * Far longer than the other thread needs to publish quiescence, so the
         * companion test fails rather than flakes if the budget starts cutting
         * a wait short that it should not.
         */
        const val GENEROUS_BUDGET_MICROS = 10_000_000L

        /** Poll interval while waiting for the detached waiter to come back. */
        const val POLL_MICROS: UInt = 1_000u

        /**
         * Long enough for the waiter to get from its offer into the spin, so
         * the quiescence it is released by arrives while it is actually
         * waiting. Well inside [GENEROUS_BUDGET_MICROS], so it cannot turn the
         * companion assertion into a timing race.
         */
        const val SPIN_ENTRY_MICROS: UInt = 20_000u

        /**
         * What "did not wait" has to fit inside. Generous next to a poll
         * quantum (50µs) so a loaded runner cannot fail it, and far below any
         * wait a regression would introduce -- the loop it would enter has
         * nothing to release it.
         */
        val NO_WAIT_CEILING = 2.seconds
    }
}
