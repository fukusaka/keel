package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.pthread_equal
import platform.posix.pthread_self
import platform.posix.pthread_t
import kotlin.coroutines.CoroutineContext
import kotlin.test.AfterTest
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The fixture shared by the [AbstractReadinessEventLoop] tests.
 *
 * Holds what the split would otherwise have duplicated five times: the seven
 * test doubles, the `loopTest` / `suspendOn` / `chainOf` helpers, the
 * constants, and the teardown that asks each loop a case built whether its
 * scratch came back -- with [owned] and [onRelease], which are how a case and
 * a fixture say a loop is theirs to give. The transport seam fixture extends this too, for [FakeLoop] —
 * one loop double serving both families rather than a near-copy per family.
 *
 * All `protected` and nested rather than hoisted to package scope, which is
 * what the sibling engine fixtures do. Two names here would not survive that:
 * `FakeLoop` is also a nested double in `LoopHandoffTest`, and `ENOMEM` would
 * sit beside `platform.posix.ENOMEM` at package scope. Nesting keeps `ENOMEM`
 * a member, so a reference resolves to it rather than to the import — the same
 * as before the split, though the two now sit in different files.
 */
@OptIn(InternalReadinessEngineApi::class)
internal abstract class AbstractReadinessEventLoopFixture {

    /**
     * Fails a case that built a loop double and did not give it back.
     *
     * A loop takes its native gather scratch in its constructor, so one left
     * open is a leak this suite could not see before — a whole class of cases
     * stayed green while leaking, and the omission was found by a reviewer
     * rather than here (#1073). [OpenTestLoops] records them; this is what
     * reads the record.
     *
     * Registration rather than a second `@AfterTest` or an overridable hook.
     * Two `@AfterTest`s run in whichever order the runner picks, and a fixture
     * that closed its own loop after this ran would be reported as leaking it
     * -- the observed runner happens to take the subclass first, which is the
     * safe order, but nothing specifies that. An overridable hook fixes the
     * order and buys a worse problem: an override that forgets `super` silently
     * drops what its parent was releasing, and that is a rule living in one
     * file again. What a fixture registers in [onRelease] cannot be forgotten
     * by the one below it -- and it must not become an `@AfterTest` again, for
     * the reason [OpenTestLoops] gives among its premises.
     *
     * They run in registration order, which the runner makes child-first: it
     * takes `@BeforeTest` from the subclass down, so a fixture releases before
     * the one it extends. Nothing here depends on that today -- each is guarded
     * and all of them run -- but it is the order, not an accident to rely on
     * silently.
     *
     * **When this line fails, whatever failed earlier in the case is not
     * reported** -- its own assertion, its `@BeforeTest`. The Native runner
     * keeps the exception from `@AfterTest` and drops the earlier one, so there
     * is no "see the real failure above". A case that fails on its own and
     * leaves nothing behind is reported normally; it is only when this line
     * also fails that the earlier one is lost. The message says so rather than sending a reader after
     * output that was never written.
     */
    @AfterTest
    fun everyLoopWasGivenBack() {
        val closeFailures = mutableListOf<Throwable>()
        val left: List<AbstractReadinessEventLoop>
        try {
            // Each guarded, and none allowed to skip the rest: a releaser that
            // threw would otherwise leave every later one and every loop this
            // case handed over unreleased, and the drain would then clear the
            // record of them -- a real leak that nothing reports. Their
            // failures are answered together.
            releasers.forEach { release -> runCatching { release() }.onFailure { closeFailures += it } }
            ownedLoops.forEach { loop -> runCatching { loop.close() }.onFailure { closeFailures += it } }
        } finally {
            // Drained whatever happened above, or this case's loops would stay
            // in a record the next case reads as its own -- one case broken and
            // the following one blamed for it.
            ownedLoops.clear()
            releasers.clear()
            left = OpenTestLoops.drain()
        }
        // Answered first, because a close that failed is why the scratch would
        // still be out: reporting the leak ahead of it would tell a case that
        // already handed its loop over to hand it over.
        assertTrue(
            closeFailures.isEmpty(),
            "giving back what this case and its fixture built failed: $closeFailures. " +
                "Anything that failed earlier in the case is not in the report either.",
        )
        assertTrue(
            left.isEmpty(),
            "a loop double still holds its gather scratch: ${left.map { it::class.simpleName }} — " +
                "close it, or hand it to owned() where it is built. If anything else in this case " +
                "failed first -- its own assertion, or its @BeforeTest -- that failure is not in " +
                "the report: the runner keeps this one instead, so look at what the case was " +
                "doing rather than at this line.",
        )
    }

    private val releasers = mutableListOf<() -> Unit>()

    /**
     * Registers something the fixture itself built, to be given back ahead of
     * the check above.
     *
     * A fixture that owns a loop or a descriptor registers its release from
     * `@BeforeTest` rather than overriding anything: there is no `super` for a
     * fixture below it to forget, and every registration runs whatever the
     * others do. See [everyLoopWasGivenBack] for why not an `@AfterTest` of
     * its own.
     */
    protected fun onRelease(block: () -> Unit) {
        releasers += block
    }

    private val ownedLoops = mutableListOf<AbstractReadinessEventLoop>()

    /**
     * Hands a loop to the fixture to close after the case.
     *
     * For the cases that build one at the top and drive it throughout, where a
     * `finally` would be more unwind than test. **Not the fixture tidying up
     * quietly**: a loop that goes through neither this nor a close of its own
     * still fails [everyLoopWasGivenBack]. What this changes is that the case
     * says whose the loop is, in one word, where it builds it.
     *
     * A case that means to end with its scratch checked out *and to be let
     * through* would need neither this nor a close, and would have to say so
     * where the teardown asks. There is none. The one case that ends holding
     * scratch it re-took after closing is not that: it is given back by this
     * handover, which is what makes it pass. Nothing here can hang, and that is a fact about the two
     * doubles rather than about the bound: their closes free the scratch and do
     * nothing else. A loop reaching this from somewhere else would owe its own
     * account of what its close does.
     */
    protected fun <L : AbstractReadinessEventLoop> owned(loop: L): L {
        ownedLoops += loop
        return loop
    }

    /**
     * Records what would have been armed instead of issuing a syscall.
     *
     * [submitArm] mirrors both engines' failure handling statement for
     * statement — the stale-registration guard they run *before* the syscall,
     * and the base's `failUnarmedWaiter` after it. The guard matters because a
     * waiter that left the chain between the append and this dispatch has
     * already been resumed, so arming it would leave a ledger entry for an fd
     * that may be gone. Diverging from that here would mean asserting a
     * contract the engines do not implement. What it does not mirror is their
     * opening `assertInEventLoop`, which the off-loop doubles here would trip.
     *
     * [onLoopThread] is what the real subclasses answer from a pthread
     * comparison. [runDispatchedInline] decides whether dispatched work runs
     * immediately or waits for [drainDispatched], which is how the window
     * between append and arm is opened.
     */
    protected class FakeLoop(
        var onLoopThread: Boolean = true,
        val runDispatchedInline: Boolean = true,
        /** Off for the transport seam tests, whose `flush()` must drain inline. */
        override val flushCoalescing: Boolean = true,
    ) : AbstractReadinessEventLoop() {

        init {
            // Recorded from here, not from a factory: the cases build these
            // with a constructor call, so a factory would only watch the ones
            // that remembered to use it. See [OpenTestLoops].
            OpenTestLoops.opened(this)
        }

        /** No thread of its own; the fixture drives the ledger and the sweep directly. */
        override fun start() = Unit

        /** No thread to stop, but the base's gather scratch is still owed back. */
        override fun close() = freeWritevScratch()

        /**
         * What a real engine's `close()` does to a loop that never had a
         * thread: run the base terminal sequence — sweep the ledgers, end the
         * waiters, publish finished and quiescent — so `runOnLoop` reads this
         * loop as stopped. The plain [close] stays shallow on purpose: the
         * transport cases call it in teardown and their loop must stay live
         * to the end of the test.
         */
        fun closeAsStoppedLoop() {
            finishWithoutRunning()
            freeWritevScratch()
        }

        /**
         * Records what killed this loop, the way an engine's poll does before
         * it breaks out — for the readers that ask a stopped loop why.
         */
        fun stageLoopFault(cause: Throwable) = recordLoopFault(cause)

        /**
         * Publishes `finished` without `quiescent`: the window a real loop is
         * in while its final drain and stop sweep run. A hand-off landing
         * here waits — with a budget, until that budget runs out — which is
         * the only way to reach the expiry branch from a double.
         */
        fun stageFinishedNotQuiescent() = publishLoopFinishedForTest()

        /** No connect path in this double. */
        override suspend fun awaitWriteReady(fd: Int, logger: Logger): Unit =
            error("this double has no connect path")

        /** What [submitArm] would have armed — the suspend path. */
        val armed = mutableListOf<Pair<Int, Interest>>()

        /** What [submitArmCallback] would have armed — the pipeline path. */
        val armedCallbacks = mutableListOf<Pair<Int, Interest>>()

        /**
         * The keys [submitArmCallback] was handed.
         *
         * Recorded because the real overrides are the parameter's only
         * consumers — on arm failure each withdraws by identity, through
         * `popCallbackIfCurrent(key, listener)` — so a
         * base that computed the key from the wrong interest would take the
         * wrong listener out, and nothing that probes through [dispatchReady]
         * (which derives its own key) could see it.
         */
        val armedCallbackKeys = mutableListOf<Long>()

        /**
         * How many times [dispatch] was called, run or not.
         *
         * A counter, not a list. The list it replaces was already cumulative
         * — the queue was separate — so this changes no assertion; measured,
         * by restoring the list and re-running. It is here because a list
         * whose name says "dispatched" invites a future test to drain it,
         * which is what the revision before that actually did.
         */
        var dispatchCount: Int = 0
            private set

        /** Not yet run. Emptied as it fills when [runDispatchedInline]. */
        private val pending = mutableListOf<Runnable>()

        /** Non-zero makes [submitArm] fail with this errno instead of arming. */
        var failArm: Int = 0

        /**
         * Makes [submitArmCallback] fail, withdrawing through the key it was
         * handed — the shape kqueue's real override uses.
         *
         * The suspend hook has had [failArm] since it was written; without the
         * twin, the key the fake records is only ever proof that a `Long`
         * arrived, never that withdrawing by it removes the right listener.
         */
        var failArmCallback: Boolean = false

        /**
         * Like [failArmCallback], but for one fd only — the multi-listener
         * server cases need one arm refused while its sibling's succeeds.
         */
        var failArmCallbackForFd: Int? = null

        /** What the engines would take back from the kernel, recorded instead. */
        val disarmed = mutableListOf<Pair<Int, Interest>>()

        /** Run inside [submitArmCallback], to observe the state the arm sees. */
        var onArmCallback: (() -> Unit)? = null

        override val logger = RecordingLogger()

        /** The WARN half of what the base logged, which is what assertions want. */
        val warnings: List<String> get() = logger.warnings

        /** ERROR-level messages, for the paths that report rather than warn. */
        val errors: List<String> get() = logger.logged.filter { it.first == LogLevel.ERROR }.map { it.second }

        override fun inEventLoop(): Boolean = onLoopThread

        /**
         * Thrown by [loopBody] instead of returning, for the tests that need
         * a loop which ended on its own rather than because it was asked to.
         *
         * The real bodies cannot be made to do this on demand: both engines
         * guard every listener and every task they run, which is the point of
         * those guards -- what gets past them is whatever nobody anticipated.
         * Staging it here is staging the *shape*, which is all the terminal
         * sequence and its readers can see.
         */
        var loopBodyFailure: Throwable? = null

        /** No kernel to wait on: the loop body and its wakeup are inert here. */
        override fun loopBody() {
            loopBodyFailure?.let { throw it }
        }

        override fun wakeup() = Unit

        override fun removeInterest(fd: Int, interest: Interest) {
            disarmed.add(fd to interest)
        }

        /**
         * The callback twin of [submitArm]; no syscall, and recorded separately.
         *
         * Separate because the two hooks are not interchangeable: epoll's overrides
         * map READ onto different masks, so a test that only knows "something was
         * armed" would pass on a registerCallback mis-wired to the suspend hook.
         */
        override fun submitArmCallback(fd: Int, interest: Interest, key: Long, listener: FdReadyListener): Throwable? {
            // No guard of its own: refusing an arm for a withdrawn listener is
            // the base's, in registerCallback, and a stub that re-implemented it
            // would be what the tests asserted on instead.
            armedCallbackKeys.add(key)
            if (failArmCallback || fd == failArmCallbackForFd) {
                return withdrawFailedCallbackArm(fd, interest, key, listener, "fake-arm", ENOMEM)
            }
            armedCallbacks.add(fd to interest)
            onArmCallback?.invoke()
            return null
        }

        /** [registrationKey] is protected on the base; this is the subclass reaching it. */
        fun keyFor(fd: Int, interest: Interest): Long = registrationKey(fd, interest)

        /** [popCallbackIfCurrent] is protected on the base; this is the subclass reaching it. */
        fun popIfCurrent(key: Long, listener: FdReadyListener): Boolean =
            withRegLock { popCallbackIfCurrent(key, listener) }

        /** [dispatchReady] is protected on the base; this is the subclass reaching it. */
        fun dispatchReadyFor(fd: Int, interest: Interest, eofFlag: Boolean) =
            dispatchReady(fd, interest, eofFlag)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount++
            pending.add(block)
            if (runDispatchedInline) drainDispatched()
        }

        /**
         * Stands in for the engines' `drainTasks`, including its shape: it
         * loops until the queue is empty, because a task that runs here can
         * dispatch another one, and it guards each task the way the
         * production drain does — a throw is recorded at WARN and the batch
         * continues. Without that guard a task's escape rides into the
         * test's own `runBlocking` machinery and aborts the harness, which
         * turns "the fake reported nothing" into an unreadable crash instead
         * of a failed assertion.
         */
        override fun drainTasks() = drainDispatched()

        @Suppress("TooGenericExceptionCaught")
        fun drainDispatched() {
            while (pending.isNotEmpty()) {
                val batch = pending.toList()
                pending.clear()
                for (block in batch) {
                    try {
                        block.run()
                    } catch (t: Throwable) {
                        logger.warn(t) { "dispatched task threw on the EventLoop" }
                    }
                }
            }
        }

        override fun submitArm(fd: Int, interest: Interest, key: Long, reg: Registration) {
            if (!withRegLock { isRegistered(key, reg) }) return

            val err = failArm
            if (err != 0) {
                // The very method both engines call: the arm-failure half of
                // submitArm lives on the base now, so this drives production
                // code rather than imitating its shape.
                failUnarmedWaiter(key, reg, IllegalStateException("arm(fd=$fd) failed: errno=$err"))
                return
            }
            armed.add(fd to interest)
        }

        /** The sweep is protected on the base; this is the subclass reaching it. */
        fun failRemainingWaiters() = failWaitersOnStoppedLoop()

        /** [register] is protected on the base; this is the subclass reaching it. */
        fun registerWaiter(fd: Int, interest: Interest, cont: CancellableContinuation<Unit>) =
            register(fd, interest, cont)

        /** The same, for a waiter that owns something for the duration of its wait. */
        fun registerOwningWaiter(
            fd: Int,
            interest: Interest,
            cont: CancellableContinuation<Unit>,
            onUndeliverable: () -> Unit,
        ) = register(fd, interest, cont, onUndeliverable)

        /** [registerIf] with an owning waiter's hook, the shape the accept-path entry now permits. */
        fun registerOwningWaiterIf(
            fd: Int,
            interest: Interest,
            cont: CancellableContinuation<Unit>,
            onUndeliverable: () -> Unit,
        ) = registerIf(fd, interest, cont, onUndeliverable = onUndeliverable, stillWanted = { true })

        /** [awaitWritableOwningFd] is protected on the base; this is the subclass reaching it. */
        suspend fun awaitOwnedWrite(fd: Int, logger: Logger) =
            awaitWritableOwningFd(fd, logger)

        /** `registerCallback` is public on the base; named here for symmetry with the waiter helpers. */
        fun registerCallbackFor(fd: Int, interest: Interest, listener: FdReadyListener) =
            registerCallback(fd, interest, listener)

        /** Pops one waiter the way a subclass's dispatch path does. */
        fun popOne(fd: Int, interest: Interest): Pair<Registration?, Boolean> {
            val key = registrationKey(fd, interest)
            return withRegLock { popHeadRegistration(key) to hasWaiters(key) }
        }

        fun waiters(fd: Int, interest: Interest): Boolean =
            withRegLock { hasWaiters(registrationKey(fd, interest)) }

        /** The callback ledger's accessors are protected; this is the subclass reaching them. */
        override fun hasCallbackRegistration(fd: Int, interest: Interest): Boolean = hasCallbackFor(fd, interest)

        fun contains(fd: Int, interest: Interest, reg: Registration): Boolean =
            withRegLock { isRegistered(registrationKey(fd, interest), reg) }

        fun drain(fd: Int, interest: Interest): List<Registration> =
            generateSequence { popOne(fd, interest).first }.toList()

        /**
         * Whether the lock can be acquired right now, so a test that left it
         * held is caught rather than passed on to the next one -- the signal
         * the teardown's old `EBUSY`-on-destroy check used to carry.
         */
        fun lockFree(): Boolean = regLockFree()

        /** [regLockBroken] for the tests. */
        fun lockBroken(): Boolean = regLockBroken()

        /**
         * Set by a test that trips the failure path on purpose, so the shared
         * teardown does not report the flag it asked for.
         */
        var lockFailureExpected = false

        /** Drives the failure path; a live mutex cannot be made to fail on demand. */
        fun reportLockFailure(operation: String, errno: Int, stillHeld: Boolean) {
            lockFailureExpected = true
            reportRegLockFailure(operation, errno, stillHeld)
        }
    }

    /**
     * Records every level, shared by both fakes.
     *
     * `isLoggable = true` matters: a fake that answers `false` short-circuits the
     * inline logging extensions before `rawLog`, so anything the base logs at
     * another level is invisible and an assertion on an empty list keeps passing
     * when a line's severity changes.
     */
    protected class RecordingLogger : Logger {

        /**
         * Every record with the throwable it carried, so a test can pin that
         * a report names its cause: a `warn(cause) { ... }` degraded to
         * `warn { ... }` reads identically in [logged] while the errno and
         * the cause are gone from the log.
         *
         * One list rather than a parallel one — indexing a second list by a
         * position found in the first is only correct while both are
         * appended together, which no type enforces and a double with a
         * thread of its own would break.
         */
        val records = mutableListOf<Triple<LogLevel, String, Throwable?>>()

        /** Level and message of every record, for the assertions that do not care about the cause. */
        val logged: List<Pair<LogLevel, String>> get() = records.map { it.first to it.second }

        val warnings: List<String> get() = records.filter { it.first == LogLevel.WARN }.map { it.second }

        /** The throwable recorded with the first WARN whose message contains [fragment], if any. */
        fun causeOfWarning(fragment: String): Throwable? =
            records.firstOrNull { it.first == LogLevel.WARN && fragment in it.second }?.third

        override fun isLoggable(level: LogLevel) = true

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            records.add(Triple(level, message.toString(), throwable))
        }
    }

    /**
     * Refuses to take a resumed continuation back, the way a dispatcher backed
     * by a shut-down executor does — the reachable shape of a resume that
     * throws in the loop's frame. Shared by the loop's guard tests and the
     * transport's waiter-answer tests, which exercise the same refusal one
     * layer apart.
     *
     * [attempts] is a plain `var`, safe only because every consumer here
     * drives a [FakeLoop] on the test thread. The engines' private copies
     * make it atomic — their dispatch runs on a real EventLoop thread while
     * the assertion reads from the test thread — and a consumer that starts
     * a real thread must do the same, not adopt this one.
     */
    protected class RefusingDispatcher : CoroutineDispatcher() {
        var attempts: Int = 0
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            attempts++
            throw InjectedFault("dispatcher refused the resumed continuation")
        }
    }

    /** Records what the base handed it, and optionally re-arms the way armRead does. */
    protected class RecordingListener(
        private val reArmOn: FakeLoop? = null,
        private val fd: Int = FD,
    ) : FdReadyListener, LoopParticipant {
        val ready = mutableListOf<Interest>()
        val peerClosed = mutableListOf<Interest>()
        var loopStopped = 0
        val order = mutableListOf<String>()

        override fun onLoopStopped() {
            loopStopped++
            order.add("onLoopStopped")
        }

        override fun onReady(interest: Interest) {
            ready.add(interest)
            order.add("onReady")
            reArmOn?.registerCallback(fd, interest, this)
        }

        override fun onPeerClosed(interest: Interest) {
            peerClosed.add(interest)
            order.add("onPeerClosed")
        }
    }

    /**
     * Re-registers from [onLoopStopped] — the path that made the sweep not a
     * fixed point. Records that it was told, so a test can tell "the ledger
     * stayed empty because the re-registration was refused" apart from "it
     * stayed empty because nothing ever called back".
     */
    protected class RegisteringOnStopListener(private val loop: FakeLoop) : FdReadyListener, LoopParticipant {
        var toldCount = 0

        override fun onReady(interest: Interest) = Unit

        override fun onLoopStopped() {
            toldCount++
            loop.registerCallback(FD, Interest.WRITE, this)
        }
    }

    /**
     * Re-arms from [onPeerClosed] rather than [onReady] — the later of the two
     * points at which a listener can put itself back in the registry, and the
     * one [RecordingListener] cannot reach.
     */
    protected class ReArmOnPeerClosedListener(
        private val loop: FakeLoop,
        private val fd: Int = FD,
    ) : FdReadyListener {
        val peerClosed = mutableListOf<Interest>()

        override fun onReady(interest: Interest) = Unit

        override fun onPeerClosed(interest: Interest) {
            peerClosed.add(interest)
            loop.registerCallback(fd, interest, this)
        }
    }

    /**
     * A subclass that leaves the base's own queue and drain in place.
     *
     * [FakeLoop] overrides both so a test can hold work between dispatch and
     * run, which means the base's `drainTasks` -- its re-entrancy claim, its
     * per-task backstop -- never executes there. This one exists to reach them.
     */
    protected class RealQueueLoop(var onLoopThread: Boolean = true) : AbstractReadinessEventLoop() {

        init {
            OpenTestLoops.opened(this)
        }

        /** Whether anything is still sitting on the base's task queue. */
        fun queueHoldsWork(): Boolean = hasTasksPending()

        /** No thread of its own; the fixture drives the ledger and the sweep directly. */
        override fun start() = Unit

        /** No thread to stop, but the base's gather scratch is still owed back. */
        override fun close() = freeWritevScratch()

        /** No connect path in this double. */
        override suspend fun awaitWriteReady(fd: Int, logger: Logger): Unit =
            error("this double has no connect path")
        val logged: List<Pair<LogLevel, String>> get() = logger.logged

        /** How many times the base's own drain ran. Teardown must not repeat it. */
        var drainCalls: Int = 0
            private set

        /** What [submitArmCallback] armed, so a test can see the real queue deliver it. */
        val armedCallbacks = mutableListOf<Pair<Int, Interest>>()

        /** Counts the wakeups [dispatch] issues, which only an off-loop caller earns. */
        var wakeups: Int = 0
            private set

        /**
         * The id the base recorded for whichever thread holds the termination
         * claim, so a test can see it published and released.
         */
        @OptIn(ExperimentalForeignApi::class)
        val recordedLoopThread: pthread_t? get() = eventLoopThread

        /**
         * `true` when the test says so, and also while this thread is the one
         * the base published — which is how the real engines answer it
         * (`pthread_equal(pthread_self(), eventLoopThread)`).
         *
         * The second half matters for the paths that run the terminal sequence
         * on a closing thread: with a fixed answer, a double could not observe
         * that the base published an identity at all, and the assertions that
         * sequence makes would fail here for a reason production does not have.
         */
        @OptIn(ExperimentalForeignApi::class)
        override fun inEventLoop(): Boolean =
            onLoopThread || eventLoopThread?.let { pthread_equal(pthread_self(), it) != 0 } == true
        override fun loopBody() = Unit

        override fun wakeup() {
            wakeups++
        }

        override fun drainTasks() {
            drainCalls++
            super.drainTasks()
        }

        override fun removeInterest(fd: Int, interest: Interest) = Unit

        override fun submitArmCallback(fd: Int, interest: Interest, key: Long, listener: FdReadyListener): Throwable? {
            armedCallbacks.add(fd to interest)
            return null
        }

        override fun submitArm(fd: Int, interest: Interest, key: Long, reg: Registration) = Unit

        override val logger = RecordingLogger()

        /** [drainTasks] is protected on the base; this is the subclass reaching it. */
        fun drain() = drainTasks()

        /**
         * Takes the registration lock. Deliberately returns nothing: a failed
         * acquire still runs the block, so any value from in there would be
         * true whatever happened -- what reports the failure is [lockBroken].
         */
        fun takeRegLock() = withRegLock { }

        /**
         * Whether the lock can be acquired right now, so a test that left it
         * held is caught rather than passed on to the next one -- the signal
         * the teardown's old `EBUSY`-on-destroy check used to carry.
         */
        fun lockFree(): Boolean = regLockFree()

        /** [regLockBroken] for the tests. */
        fun lockBroken(): Boolean = regLockBroken()

        /** Drives the failure path; a live mutex cannot be made to fail on demand. */
        fun reportLockFailure(operation: String, errno: Int, stillHeld: Boolean) =
            reportRegLockFailure(operation, errno, stillHeld)
    }

    /**
     * A suspended caller: its [Registration], and a handle that completes when
     * its continuation is resumed — normally, or with the failure it was given.
     */
    protected class Waiter(
        val reg: Registration,
        val resumed: CompletableDeferred<Unit>,
    )

    /**
     * Runs [block] with a scope whose waiters are cancelled at the end, and
     * checks the registration lock survived it.
     *
     * Most waiters here are deliberately never resumed; that is the state under
     * test. They are launched into a scope of their own rather than the
     * timeout's, because `withTimeout` waits for its children and would report
     * a parked waiter as a timeout instead of letting the test assert on it.
     *
     * Waiters are cancelled and joined first so no cancellation handler runs
     * into the next test's fake — [suspendOn] installs the real
     * `invokeOnCancellation { unregister(reg) }`, which takes the registration
     * lock. That lock is never freed, so the ordering is about test isolation
     * rather than memory safety; what the teardown then checks is that no test
     * left it broken or held.
     */
    protected fun loopTest(block: suspend CoroutineScope.(FakeLoop) -> Unit) = loopTestWith(FakeLoop(), block)

    protected fun loopTestWith(loop: FakeLoop, block: suspend CoroutineScope.(FakeLoop) -> Unit) = runBlocking {
        withTimeout(TEST_BUDGET) {
            val waiters = CoroutineScope(coroutineContext + Job())
            try {
                waiters.block(loop)
            } finally {
                // join, not just cancel: suspendOn installs the production
                // invokeOnCancellation { unregister(reg) }, so joining keeps a
                // handler from running into the next test's fake. It no longer
                // orders anything against a teardown — the registration lock is
                // never freed — but leaving waiters mid-cancel across tests is
                // its own flake source.
                //
                // NonCancellable because this finally also runs on the timeout
                // path, where the enclosing coroutine is already cancelled and a
                // bare join() throws at once.
                waiters.cancel()
                withContext(NonCancellable) { waiters.coroutineContext.job.join() }
                // The loop this helper handed out is the helper's to give
                // back. Here rather than after the lock checks below, so a
                // case that fails one of them still returns the scratch.
                loop.close()
            }
            // The lock outlives every test: nothing frees it, so a fake that
            // reports a failure means this class broke its own exclusion. The
            // second check is the one with teeth on a healthy mutex -- it fails
            // when a test leaves the lock held, which is what the teardown's old
            // `EBUSY`-on-destroy reported.
            if (!loop.lockFailureExpected) {
                assertFalse(loop.lockBroken(), "no test may leave the registration lock broken")
            }
            assertTrue(loop.lockFree(), "no test may leave the registration lock held")
        }
    }

    /**
     * Suspends a caller on [fd] + [interest], with the same cancellation
     * handler every production caller installs.
     */
    protected fun CoroutineScope.suspendOn(loop: FakeLoop, fd: Int, interest: Interest): CompletableDeferred<Waiter> {
        val handle = CompletableDeferred<Waiter>()
        launch {
            val resumed = CompletableDeferred<Unit>()
            try {
                suspendCancellableCoroutine { cont ->
                    val reg = loop.registerWaiter(fd, interest, cont)
                    cont.invokeOnCancellation { loop.unregister(reg) }
                    handle.complete(Waiter(reg, resumed))
                }
                resumed.complete(Unit)
            } catch (t: Throwable) {
                resumed.completeExceptionally(t)
            }
        }
        return handle
    }

    /**
     * Asserts [handle] carries the cancellation the sweep hands out.
     *
     * The type alone proves nothing, twice over. `IllegalStateException` — the
     * first shape this had — is satisfied by anything that cancels the waiter,
     * because on this target `CancellationException` **is** an
     * `IllegalStateException`: measured, with the sweep body emptied two of
     * these tests passed that assertion and failed only on their other checks,
     * 15 s later. And the sweep cancels with a `CancellationException` on
     * purpose, so that is not distinguishing either. The message is what tells
     * this sweep apart from every other way a waiter can end.
     */
    protected suspend fun assertSweptFailure(handle: CompletableDeferred<Unit>) {
        val failure = assertFailsWith<CancellationException> { handle.await() }
        assertTrue(
            failure.message?.contains(SWEEP_FAILURE) == true,
            "expected the sweep's cancellation, got: $failure",
        )
    }

    protected suspend fun CoroutineScope.chainOf(loop: FakeLoop, size: Int, interest: Interest = Interest.READ) =
        (0 until size).map { suspendOn(loop, FD, interest).await() }

    protected companion object {
        /**
         * Everything here completes on the calling thread — no fd, no kernel, no
         * loop thread — so this bounds a bug, not a slow operation.
         */
        val TEST_BUDGET = 15.seconds

        const val FD = 42

        /** Any non-zero errno; the value only has to reach the waiter's message. */
        const val ENOMEM = 12

        /** The stable prefix of the failure the sweep hands its waiters. */
        const val SWEEP_FAILURE = "EventLoop stopped before arming"
    }
}
