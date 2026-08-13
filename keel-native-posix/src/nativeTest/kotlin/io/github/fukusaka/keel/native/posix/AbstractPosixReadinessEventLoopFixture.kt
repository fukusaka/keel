package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The fixture shared by the [AbstractPosixReadinessEventLoop] tests.
 *
 * Holds what the split would otherwise have duplicated four times: the seven
 * test doubles, the `loopTest` / `suspendOn` / `chainOf` helpers, and the
 * constants.
 *
 * All `protected` and nested rather than hoisted to package scope, which is
 * what the sibling engine fixtures do. Two names here would not survive that:
 * `FakeLoop` is also a nested double in `LoopHandoffTest`, and `ENOMEM` would
 * sit beside `platform.posix.ENOMEM` at package scope. Nesting keeps `ENOMEM`
 * a member, so a reference resolves to it rather than to the import — the same
 * as before the split, though the two now sit in different files.
 */
@OptIn(InternalPosixEventLoopApi::class)
internal abstract class AbstractPosixReadinessEventLoopFixture {

    /**
     * Records what would have been armed instead of issuing a syscall.
     *
     * [submitArm] mirrors both engines statement for statement, including the
     * stale-registration guard they run *before* the syscall: a waiter that
     * left the chain between the append and this dispatch has already been
     * resumed, so arming it would leave a ledger entry for an fd that may be
     * gone. Diverging from that here would mean asserting a contract the
     * engines do not implement.
     *
     * [onLoopThread] is what the real subclasses answer from a pthread
     * comparison. [runDispatchedInline] decides whether dispatched work runs
     * immediately or waits for [drainDispatched], which is how the window
     * between append and arm is opened.
     */
    protected class FakeLoop(
        var onLoopThread: Boolean = true,
        val runDispatchedInline: Boolean = true,
    ) : AbstractPosixReadinessEventLoop() {

        /** No thread of its own; the fixture drives the ledger and the sweep directly. */
        override fun start() = Unit

        /** No thread of its own; the fixture drives the ledger and the sweep directly. */
        override fun close() = Unit

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
         * consumers — on arm failure each withdraws `popCallback(key)` — so a
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

        /** No kernel to wait on: the loop body and its wakeup are inert here. */
        override fun loopBody() = Unit

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
        override fun submitArmCallback(fd: Int, interest: Interest, key: Long, listener: FdReadyListener) {
            // No guard of its own: refusing an arm for a withdrawn listener is
            // the base's, in registerCallback, and a stub that re-implemented it
            // would be what the tests asserted on instead.
            armedCallbackKeys.add(key)
            if (failArmCallback) {
                withdrawFailedCallbackArm(fd, interest, key, listener, "fake-arm", ENOMEM)
                return
            }
            armedCallbacks.add(fd to interest)
            onArmCallback?.invoke()
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
         * dispatch another one.
         */
        override fun drainTasks() = drainDispatched()

        fun drainDispatched() {
            while (pending.isNotEmpty()) {
                val batch = pending.toList()
                pending.clear()
                for (block in batch) block.run()
            }
        }

        override fun submitArm(
            fd: Int,
            interest: Interest,
            key: Long,
            reg: Registration,
            cont: CancellableContinuation<Unit>,
        ) {
            if (!withRegLock { isRegistered(key, reg) }) return

            val err = failArm
            if (err != 0) {
                withRegLock { removeRegistration(key, reg) }
                cont.resumeWith(Result.failure(IllegalStateException("arm(fd=$fd) failed: errno=$err")))
                return
            }
            armed.add(fd to interest)
        }

        /** The sweep is protected on the base; this is the subclass reaching it. */
        fun failRemainingWaiters() = failWaitersOnStoppedLoop()

        /** [register] is protected on the base; this is the subclass reaching it. */
        fun registerWaiter(fd: Int, interest: Interest, cont: CancellableContinuation<Unit>) =
            register(fd, interest, cont)

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
        val logged = mutableListOf<Pair<LogLevel, String>>()

        val warnings: List<String> get() = logged.filter { it.first == LogLevel.WARN }.map { it.second }

        override fun isLoggable(level: LogLevel) = true

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            logged.add(level to message.toString())
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
    protected class RealQueueLoop(var onLoopThread: Boolean = true) : AbstractPosixReadinessEventLoop() {

        /** No thread of its own; the fixture drives the ledger and the sweep directly. */
        override fun start() = Unit

        /** No thread of its own; the fixture drives the ledger and the sweep directly. */
        override fun close() = Unit

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

        override fun submitArmCallback(fd: Int, interest: Interest, key: Long, listener: FdReadyListener) {
            armedCallbacks.add(fd to interest)
        }

        override fun submitArm(
            fd: Int,
            interest: Interest,
            key: Long,
            reg: Registration,
            cont: CancellableContinuation<Unit>,
        ) = Unit

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
        val reg: AbstractPosixReadinessEventLoop.Registration,
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
