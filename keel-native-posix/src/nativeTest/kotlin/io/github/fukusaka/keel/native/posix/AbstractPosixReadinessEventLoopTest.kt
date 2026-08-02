package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
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
import platform.posix.pthread_t
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Chain bookkeeping of [AbstractPosixReadinessEventLoop], driven directly.
 *
 * The class has seven abstract members, so a subclass can exercise every
 * transition of the FIFO chain with no fd, no kernel and no loop thread. That
 * matters because the transitions carry a head-and-tail transfer whose failure
 * mode is silent: a detached tail makes later appends land where nothing will
 * pop them, and the waiter's `accept()` hangs instead of failing.
 *
 * Chains of one, two, three and four nodes are all built. Four is not padding:
 * it is the shortest chain in which a removal target is genuinely interior —
 * neither the head nor the tail — which is the only shape that exercises the
 * walk past a second hop and the tail fixup independently of each other.
 */
@OptIn(InternalPosixEventLoopApi::class)
class AbstractPosixReadinessEventLoopTest {

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
    private class FakeLoop(
        var onLoopThread: Boolean = true,
        val runDispatchedInline: Boolean = true,
    ) : AbstractPosixReadinessEventLoop() {
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
        fun hasCallbackRegistration(fd: Int, interest: Interest): Boolean = hasCallbackFor(fd, interest)

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
    private class RecordingLogger : Logger {
        val logged = mutableListOf<Pair<LogLevel, String>>()

        val warnings: List<String> get() = logged.filter { it.first == LogLevel.WARN }.map { it.second }

        override fun isLoggable(level: LogLevel) = true

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            logged.add(level to message.toString())
        }
    }

    /** Records what the base handed it, and optionally re-arms the way armRead does. */
    private class RecordingListener(
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
    private class RegisteringOnStopListener(private val loop: FakeLoop) : FdReadyListener, LoopParticipant {
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
    private class ReArmOnPeerClosedListener(
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
    private class RealQueueLoop(var onLoopThread: Boolean = true) : AbstractPosixReadinessEventLoop() {
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
         * The id the base recorded for its loop thread, so a test can see it
         * released when the loop exits. [inEventLoop] is overridden here, so
         * nothing else in this double reads it.
         */
        @OptIn(ExperimentalForeignApi::class)
        val recordedLoopThread: pthread_t? get() = eventLoopThread

        override fun inEventLoop(): Boolean = onLoopThread
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
    private class Waiter(
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
    private fun loopTest(block: suspend CoroutineScope.(FakeLoop) -> Unit) = loopTestWith(FakeLoop(), block)

    private fun loopTestWith(loop: FakeLoop, block: suspend CoroutineScope.(FakeLoop) -> Unit) = runBlocking {
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
    private fun CoroutineScope.suspendOn(loop: FakeLoop, fd: Int, interest: Interest): CompletableDeferred<Waiter> {
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
    private suspend fun assertSweptFailure(handle: CompletableDeferred<Unit>) {
        val failure = assertFailsWith<CancellationException> { handle.await() }
        assertTrue(
            failure.message?.contains(SWEEP_FAILURE) == true,
            "expected the sweep's cancellation, got: $failure",
        )
    }

    private suspend fun CoroutineScope.chainOf(loop: FakeLoop, size: Int, interest: Interest = Interest.READ) =
        (0 until size).map { suspendOn(loop, FD, interest).await() }

    @Test
    fun `a single waiter is popped and the key becomes empty`() = loopTest { loop ->
        val w = suspendOn(loop, FD, Interest.READ).await()

        assertEquals(listOf(FD to Interest.READ), loop.armed, "register should arm exactly once")
        assertTrue(loop.waiters(FD, Interest.READ))

        val (popped, more) = loop.popOne(FD, Interest.READ)
        assertSame(w.reg, popped)
        assertFalse(more, "the last waiter leaves the key empty")
        assertNull(loop.popOne(FD, Interest.READ).first, "a second pop finds nothing")
    }

    @Test
    fun `waiters on one key are popped in the order they registered`() = loopTest { loop ->
        val regs = chainOf(loop, 3).map { it.reg }
        assertEquals(regs, loop.drain(FD, Interest.READ))
    }

    @Test
    fun `popping the head of a two-node chain leaves the survivor appendable`() = loopTest { loop ->
        // Two nodes is the case where the tail pointer has to be cleared rather
        // than inherited: the new head IS the tail. Inheriting it leaves a stale
        // tail, and the append below lands behind it, unreachable.
        val (first, second) = chainOf(loop, 2)

        assertSame(first.reg, loop.popOne(FD, Interest.READ).first)
        val third = suspendOn(loop, FD, Interest.READ).await()

        assertEquals(listOf(second.reg, third.reg), loop.drain(FD, Interest.READ), "the late append must still be reachable")
    }

    @Test
    fun `popping the head of a longer chain keeps the tail reachable`() = loopTest { loop ->
        // Three nodes is the case two cannot catch. There the new head really is
        // the tail, so clearing the pointer and inheriting it look the same.
        // With three, the new head must inherit the old tail: losing it makes
        // the next append overwrite the middle node's successor instead of
        // following it, and that waiter is never popped again.
        val chain = chainOf(loop, 3)
        assertSame(chain[0].reg, loop.popOne(FD, Interest.READ).first)
        val late = suspendOn(loop, FD, Interest.READ).await()

        assertEquals(
            listOf(chain[1].reg, chain[2].reg, late.reg),
            loop.drain(FD, Interest.READ),
            "appending after a pop must not detach the waiter that was already last",
        )
    }

    @Test
    fun `unregister removes the head and leaves the rest in order`() = loopTest { loop ->
        val chain = chainOf(loop, 4)
        loop.unregister(chain[0].reg)

        assertFalse(loop.contains(FD, Interest.READ, chain[0].reg))
        val late = suspendOn(loop, FD, Interest.READ).await()
        assertEquals(listOf(chain[1].reg, chain[2].reg, chain[3].reg, late.reg), loop.drain(FD, Interest.READ))
    }

    @Test
    fun `unregister removes an interior waiter and leaves the rest in order`() = loopTest { loop ->
        // Four nodes is the shortest chain with a target that is neither head
        // nor tail, so the walk goes past a second hop and the tail fixup is
        // not involved. Three nodes cannot separate the two.
        val chain = chainOf(loop, 4)
        loop.unregister(chain[2].reg)

        assertFalse(loop.contains(FD, Interest.READ, chain[2].reg))
        val late = suspendOn(loop, FD, Interest.READ).await()
        assertEquals(listOf(chain[0].reg, chain[1].reg, chain[3].reg, late.reg), loop.drain(FD, Interest.READ))
    }

    @Test
    fun `unregister removes the tail and a later append still lands behind it`() = loopTest { loop ->
        // Removing the tail is the case that needs head.tail moved back to the
        // new last node. Leaving it pointing at the removed node makes the next
        // append attach to something already detached, so it is never popped.
        val chain = chainOf(loop, 4)
        loop.unregister(chain[3].reg)

        assertFalse(loop.contains(FD, Interest.READ, chain[3].reg))
        val late = suspendOn(loop, FD, Interest.READ).await()
        assertEquals(listOf(chain[0].reg, chain[1].reg, chain[2].reg, late.reg), loop.drain(FD, Interest.READ))
    }

    @Test
    fun `unregistering the same waiter twice is a no-op`() = loopTest { loop ->
        val only = suspendOn(loop, FD, Interest.READ).await()
        loop.unregister(only.reg)
        loop.unregister(only.reg)
        assertNull(loop.popOne(FD, Interest.READ).first)
    }

    @Test
    fun `unregister uses the interest the waiter registered with`() = loopTest { loop ->
        // unregister is the only member that derives the key from the
        // Registration rather than being handed one. A waiter cancelled on the
        // connect path registers WRITE; deriving READ instead would leave it in
        // the chain forever while appearing to succeed.
        val reader = suspendOn(loop, FD, Interest.READ).await()
        val writer = suspendOn(loop, FD, Interest.WRITE).await()

        loop.unregister(writer.reg)

        assertFalse(loop.waiters(FD, Interest.WRITE), "the WRITE waiter is gone")
        assertTrue(loop.contains(FD, Interest.READ, reader.reg), "the READ waiter is untouched")
    }

    @Test
    fun `read and write on the same fd are separate chains`() = loopTest { loop ->
        val reader = suspendOn(loop, FD, Interest.READ).await()
        val writer = suspendOn(loop, FD, Interest.WRITE).await()

        assertSame(reader.reg, loop.popOne(FD, Interest.READ).first)
        assertTrue(loop.waiters(FD, Interest.WRITE), "popping READ must not touch WRITE")
        assertSame(writer.reg, loop.popOne(FD, Interest.WRITE).first)
    }

    @Test
    fun `cancelAll fails every waiter on the key and empties the chain`() = loopTest { loop ->
        val waiters = chainOf(loop, 3)
        val untouched = suspendOn(loop, FD, Interest.WRITE).await()

        loop.cancelAll(FD, Interest.READ, IllegalStateException("server closed"))

        for (w in waiters) {
            assertFailsWith<IllegalStateException> { w.resumed.await() }
        }
        assertNull(loop.popOne(FD, Interest.READ).first, "the chain is empty afterwards")
        assertTrue(loop.waiters(FD, Interest.WRITE), "the other interest is untouched")
        assertFalse(untouched.resumed.isCompleted)
    }

    @Test
    fun `registerIf appends and arms when wanted`() = loopTest { loop ->
        val accepted = CompletableDeferred<AbstractPosixReadinessEventLoop.Registration?>()
        launch {
            suspendCancellableCoroutine { cont ->
                accepted.complete(loop.registerIf(FD, Interest.READ, cont) { true })
            }
        }
        val reg = accepted.await()

        assertEquals(listOf(FD to Interest.READ), loop.armed, "an accepted registration must be armed")
        assertSame(reg, loop.popOne(FD, Interest.READ).first)
    }

    @Test
    fun `registerIf declines without appending or arming`() = loopTest { loop ->
        val declined = CompletableDeferred<AbstractPosixReadinessEventLoop.Registration?>()
        launch {
            suspendCancellableCoroutine { cont ->
                declined.complete(loop.registerIf(FD, Interest.READ, cont) { false })
                cont.resumeWith(Result.success(Unit))
            }
        }

        assertNull(declined.await(), "a declined registration returns null")
        assertFalse(loop.waiters(FD, Interest.READ), "and appends nothing")
        assertTrue(loop.armed.isEmpty(), "and arms nothing")
    }

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
        assertTrue(failure.message?.contains("errno=$ENOMEM") == true, "the errno reaches the waiter: ${failure.message}")
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

    // --- the two guards on the base's own queue and loop ---

    @Test
    fun `a task that drains again does not lose the batch it re-entered`() {
        // drainTasks is re-entrant from a task it is running. The outer call
        // already drains until the queue is empty, so the inner one has nothing
        // left to do -- and must not clear the shared batch under the iteration.
        val loop = RealQueueLoop()
        val ran = mutableListOf<String>()
        loop.dispatch(EmptyCoroutineContext, Runnable { ran.add("first") })
        loop.dispatch(EmptyCoroutineContext, Runnable { ran.add("re-enters"); loop.drain() })
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
        // so a second entry is reported and ignored rather than thrown -- and it
        // must not re-point the thread identity the whole class reads.
        val loop = RealQueueLoop()
        loop.loop()
        val errorsAfterFirst = loop.logged.count { it.first == LogLevel.ERROR }

        loop.loop()

        assertEquals(0, errorsAfterFirst, "the first entry is not an error")
        assertTrue(
            loop.logged.any { it.first == LogLevel.ERROR && it.second.contains("entered twice") },
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
    }

    // --- the pipeline path, which moved onto the base with this class ---

    @Test
    fun `a callback is registered before it is armed`() = loopTest { loop ->
        // The order is the contract: the arm can report readiness the instant
        // the kernel accepts it, so a listener that is not in the map yet would
        // be a dropped event. The fake records the arm, so seeing the listener
        // already present when it runs is what pins the order.
        var registeredWhenArmed = false
        loop.onArmCallback = { registeredWhenArmed = loop.hasCallbackRegistration(FD, Interest.READ) }

        loop.registerCallback(FD, Interest.READ, RecordingListener())

        assertTrue(registeredWhenArmed, "the listener must be in the map before the arm runs")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `an off-loop registerCallback arms through the funnel`() = loopTestWith(
        FakeLoop(onLoopThread = false),
    ) { loop ->
        // The pipeline twin of the suspend path's funnel test. registerCallback is
        // the member that moved, and its only non-trivial behaviour is the fork in
        // submitOnLoop -- every other test here runs on-loop, so the branch that
        // captures fd/interest/key into a Runnable was never taken.
        val listener = RecordingListener()

        loop.registerCallback(FD, Interest.READ, listener)

        assertEquals(1, loop.dispatchCount, "an off-loop registration goes through dispatch")
        assertEquals(
            listOf(FD to Interest.READ),
            loop.armedCallbacks,
            "through the callback hook, not the suspend one -- epoll maps READ differently on each",
        )
        assertTrue(loop.armed.isEmpty(), "the suspend hook is not the one that fires")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `a second registration on one key replaces the first`() = loopTest { loop ->
        // The contract registerCallback documents, and the one every re-arm
        // depends on. The re-arm tests cannot see it: they re-register the same
        // object, so "registered afterwards" holds whether the ledger replaced,
        // kept or chained. Two distinct listeners is what separates those.
        val replaced = RecordingListener()
        val current = RecordingListener()
        loop.registerCallback(FD, Interest.READ, replaced)
        loop.registerCallback(FD, Interest.READ, current)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(listOf(Interest.READ), current.ready, "the later registration is the one that runs")
        assertEquals(emptyList(), replaced.ready, "and the one it displaced is never called, nor told")
    }

    @Test
    fun `the arm is handed the key of the interest it is arming`() = loopTest { loop ->
        // Both real overrides withdraw `popCallback(key)` when the arm fails,
        // so a key derived from the wrong interest takes the wrong listener out
        // of the ledger. Nothing else here can see that: every
        // other probe goes through dispatchReady, which computes its own key.
        loop.registerCallback(FD, Interest.READ, RecordingListener())
        loop.registerCallback(FD, Interest.WRITE, RecordingListener())

        assertEquals(
            listOf(loop.keyFor(FD, Interest.READ), loop.keyFor(FD, Interest.WRITE)),
            loop.armedCallbackKeys,
            "each arm gets the key for its own interest, in registration order",
        )
    }

    @Test
    fun `a failed arm withdraws the listener for that interest and no other`() = loopTest { loop ->
        // What the recorded key is actually for. Both engines withdraw
        // `popCallback(key)` when the arm fails, so a base handing over a key
        // built from the wrong interest silently removes the wrong listener --
        // and both would still look armed from dispatchReady, which derives its
        // own key.
        loop.registerCallback(FD, Interest.READ, RecordingListener())
        loop.failArmCallback = true

        loop.registerCallback(FD, Interest.WRITE, RecordingListener())

        // Pins the interest the key encodes. Which listener a failed arm
        // withdraws is pinned separately, by the identity test below.
        assertFalse(loop.hasCallbackRegistration(FD, Interest.WRITE), "the failed arm takes its own listener out")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ), "and leaves the other interest alone")

        // What the base then does with a readiness event for the withdrawn
        // interest: nothing is registered, so it warns and takes the kernel
        // interest back rather than re-firing forever.
        loop.dispatchReadyFor(FD, Interest.WRITE, eofFlag = false)

        assertEquals(listOf(FD to Interest.WRITE), loop.disarmed)
        assertTrue(loop.warnings.any { it.contains("no handler") }, "the withdrawal must be visible: ${loop.warnings}")
    }

    @Test
    fun `a queued arm does not fire for a listener that was replaced`() = loopTestWith(
        FakeLoop(onLoopThread = false, runDispatchedInline = false),
    ) { loop ->
        // Why the guard is by identity and not by presence. The ledger holds one
        // entry per key, so a replacement passes a presence test -- and then it
        // is the entry an arm failure withdraws. Weakening the check to
        // hasCallbackListener(key) leaves every other test in this file green.
        val replaced = RecordingListener()
        loop.registerCallback(FD, Interest.READ, replaced)
        loop.unregisterCallback(FD, Interest.READ)
        val current = RecordingListener()
        loop.onLoopThread = true
        loop.registerCallback(FD, Interest.READ, current)
        loop.armedCallbacks.clear()
        loop.onLoopThread = false

        loop.drainDispatched()

        assertTrue(
            loop.armedCallbacks.isEmpty(),
            "the queued arm belonged to a listener that is gone; it must not arm on the replacement's behalf",
        )
    }

    @Test
    fun `a failed arm withdraws its own listener and never a replacement`() = loopTest { loop ->
        // The other half of the identity rule. The pre-arm check keeps a queued
        // arm from firing for a listener that is gone; this keeps a *failing*
        // arm from taking the entry that superseded it. Withdrawing by key alone
        // passes both engines' seam tests -- there is one listener there -- and
        // silently evicts a replacement whose own arm already succeeded, which
        // no error names, because the error names the listener that failed.
        val superseded = RecordingListener()
        val replacement = RecordingListener()
        loop.registerCallback(FD, Interest.READ, superseded)
        loop.registerCallback(FD, Interest.READ, replacement)
        val key = loop.keyFor(FD, Interest.READ)

        assertFalse(
            loop.popIfCurrent(key, superseded),
            "the superseded listener is not on the key, so its failure withdraws nothing",
        )
        assertTrue(
            loop.hasCallbackRegistration(FD, Interest.READ),
            "and the replacement stays registered, armed, and reachable",
        )

        assertTrue(loop.popIfCurrent(key, replacement), "the entry that is there is withdrawable by its owner")
        assertFalse(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `an off-loop registerCallback does not arm until the loop drains it`() = loopTestWith(
        FakeLoop(onLoopThread = false, runDispatchedInline = false),
    ) { loop ->
        // The pipeline twin of the suspend path's deferred-arm test, and the
        // window the callback path's missing stale-registration guard lives in:
        // the listener is in the ledger and the kernel knows nothing yet, so a
        // teardown landing here withdraws a listener whose arm still runs.
        val listener = RecordingListener()

        loop.registerCallback(FD, Interest.READ, listener)

        assertEquals(1, loop.dispatchCount, "the arm is queued, not run")
        assertTrue(loop.armedCallbacks.isEmpty(), "nothing reaches the kernel while it sits there")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ), "but the listener is already in the ledger")

        loop.unregisterCallback(FD, Interest.READ)
        loop.drainDispatched()

        assertTrue(
            loop.armedCallbacks.isEmpty(),
            "a withdrawn listener's queued arm must not reach the kernel -- the fd may be closed by then",
        )
        assertFalse(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `unregisterCallback drops only the matching interest`() = loopTest { loop ->
        loop.registerCallback(FD, Interest.READ, RecordingListener())
        loop.registerCallback(FD, Interest.WRITE, RecordingListener())

        loop.unregisterCallback(FD, Interest.READ)

        assertFalse(loop.hasCallbackRegistration(FD, Interest.READ))
        assertTrue(loop.hasCallbackRegistration(FD, Interest.WRITE), "the other half must survive")
    }

    @Test
    fun `readiness reaches the listener and disarms when it does not re-arm`() = loopTest { loop ->
        val listener = RecordingListener()
        loop.registerCallback(FD, Interest.WRITE, listener)

        loop.dispatchReadyFor(FD, Interest.WRITE, eofFlag = false)

        assertEquals(listOf(Interest.WRITE), listener.ready)
        assertEquals(emptyList(), listener.peerClosed)
        assertEquals(listOf(FD to Interest.WRITE), loop.disarmed, "a callback that does not re-arm is taken back")
    }

    @Test
    fun `a listener that re-arms during onReady keeps its interest`() = loopTest { loop ->
        // What a READ callback does every time, via armRead. Disarming here
        // would discard a live registration.
        val listener = RecordingListener(reArmOn = loop)
        loop.registerCallback(FD, Interest.READ, listener)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(emptyList(), loop.disarmed, "the re-armed interest must not be taken back")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `eof reaches onPeerClosed after onReady`() = loopTest { loop ->
        // Order matters for a combined data-and-EOF event: drain before close.
        val listener = RecordingListener()
        loop.registerCallback(FD, Interest.READ, listener)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = true)

        assertEquals(listOf(Interest.READ), listener.ready)
        assertEquals(listOf(Interest.READ), listener.peerClosed)
        assertEquals(listOf("onReady", "onPeerClosed"), listener.order)
    }

    @Test
    fun `eof does not disarm a listener that re-armed`() = loopTest { loop ->
        // The regression the comment on dispatchReady records: eof used to
        // disarm unconditionally, on the reasoning that a connection reporting
        // EOF is ending. A server's AcceptArm re-arms on both WouldBlock and a
        // failed accept, putting itself straight back into the registry -- so
        // disarming here discarded a live registration and left an accept loop
        // that never ran again.
        val listener = RecordingListener(reArmOn = loop)
        loop.registerCallback(FD, Interest.READ, listener)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = true)

        assertEquals(listOf(Interest.READ), listener.peerClosed, "the close still reaches the listener")
        assertEquals(emptyList(), loop.disarmed, "but a re-armed interest must survive it")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `eof is delivered on the write interest too`() = loopTest { loop ->
        // The base delivers eof on whichever interest the event arrived on, and
        // both engines do pass the flag on their write filter, so this pins the
        // dispatch contract rather than a transport outcome.
        //
        // No transport reacts to a WRITE eof today: both onPeerClosed overrides
        // return early on anything but READ. What the test holds is that the
        // base does not silently drop half the parameter's domain.
        val listener = RecordingListener()
        loop.registerCallback(FD, Interest.WRITE, listener)

        loop.dispatchReadyFor(FD, Interest.WRITE, eofFlag = true)

        assertEquals(listOf(Interest.WRITE), listener.peerClosed, "peer close must reach a write-only listener")
        assertEquals(listOf("onReady", "onPeerClosed"), listener.order)
        // The same assertion the non-eof sibling makes. Without it, skipping the
        // disarm when eofFlag is set passes here -- which is the pre-#449 bug
        // inverted, and this branch used to be written separately per engine.
        assertEquals(listOf(FD to Interest.WRITE), loop.disarmed, "and the interest is still taken back")
    }

    @Test
    fun `a listener that re-arms during onPeerClosed keeps its interest`() = loopTest { loop ->
        // The later of the two re-arm points, and the one the sibling tests
        // cannot see: their listener re-arms during onReady, so they hold the
        // probe only against being moved before that. This one holds it against
        // being moved between the two callbacks.
        //
        // No in-tree listener re-arms from onPeerClosed today -- SuspendBridgeHandler
        // deliberately does not, and a test asserts that. So this pins the contract
        // the interface states (a listener may re-arm from either callback) rather
        // than a live path, and it is the contract that makes the probe's position
        // load-bearing for anyone who writes such a listener.
        val listener = ReArmOnPeerClosedListener(loop)
        loop.registerCallback(FD, Interest.READ, listener)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = true)

        assertEquals(listOf(Interest.READ), listener.peerClosed)
        assertEquals(emptyList(), loop.disarmed, "a re-arm from onPeerClosed must survive the probe")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `readiness pops one suspend waiter and leaves the interest armed for its siblings`() =
        loopTest { loop ->
            // The suspend arm of dispatchReady, which the callback tests never
            // reach. Two waiters on one key is the concurrent-accept() shape: the
            // first is resumed, the interest stays armed so the next wait
            // cascade-fires the second.
            val first = suspendOn(loop, FD, Interest.READ).await()
            val second = suspendOn(loop, FD, Interest.READ).await()

            loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

            // await rather than isCompleted: the resume schedules the waiter's
            // coroutine, which has to run before it completes its handle.
            first.resumed.await()
            yield()
            assertFalse(second.resumed.isCompleted, "its sibling waits for the next event")
            assertEquals(emptyList(), loop.disarmed, "and the interest stays armed while it does")
        }

    @Test
    fun `readiness takes the interest back once the last suspend waiter is gone`() = loopTest { loop ->
        // The other side of the same decision: with the chain empty there is
        // nothing to cascade to, so leaving it armed is the level-triggered busy
        // loop the KDoc describes.
        val only = suspendOn(loop, FD, Interest.READ).await()

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        only.resumed.await()
        assertEquals(listOf(FD to Interest.READ), loop.disarmed, "the last waiter takes the interest with it")
    }

    @Test
    fun `readiness with no handler at all disarms and warns`() = loopTest { loop ->
        // The stale-interest safety net: nothing registered, so the kernel would
        // keep re-firing until the fd closed.
        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(listOf(FD to Interest.READ), loop.disarmed)
        assertTrue(loop.warnings.any { it.contains("no handler") }, "the broken invariant must be visible: ${loop.warnings}")
    }

    @Test
    fun `readiness prefers the callback over a suspend waiter on the same key`() = loopTest { loop ->
        // Precedence only: the callback wins the dispatch and the waiter stays
        // queued. What happens to the interest when the callback declines to
        // re-arm is the sibling test below.
        val listener = RecordingListener(reArmOn = loop)
        loop.registerCallback(FD, Interest.READ, listener)
        val waiter = suspendOn(loop, FD, Interest.READ).await()

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(listOf(Interest.READ), listener.ready)
        assertTrue(loop.waiters(FD, Interest.READ), "the suspend waiter must still be queued")
        assertFalse(waiter.resumed.isCompleted)
    }

    @Test
    fun `a callback that does not re-arm leaves the interest for a waiting sibling`() = loopTest { loop ->
        // The callback wins the dispatch and declines to re-arm, but a suspend
        // waiter is queued on the same key. Taking the interest back here strands
        // it: nothing re-arms, so its continuation is never resumed and never
        // failed.
        loop.registerCallback(FD, Interest.READ, RecordingListener())
        val waiter = suspendOn(loop, FD, Interest.READ).await()

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(emptyList(), loop.disarmed, "a queued waiter still needs the interest armed")
        assertTrue(loop.waiters(FD, Interest.READ), "and it is still in the chain")
        assertFalse(waiter.resumed.isCompleted)
    }

    @Test
    fun `the sweep withdraws every callback the loop will never dispatch`() = loopTest { loop ->
        // The pipeline half of what the suspend sweep does. A listener left in
        // the ledger is not merely un-notified: it holds the transport, and the
        // transport holds the channel and the pipeline graph behind it, for as
        // long as the stopped loop object is alive.
        val listener = RecordingListener()
        loop.registerCallback(FD, Interest.READ, listener)
        loop.registerCallback(FD, Interest.WRITE, listener)

        loop.failRemainingWaiters()

        assertFalse(loop.hasCallbackRegistration(FD, Interest.READ), "the callback ledger is emptied")
        assertFalse(loop.hasCallbackRegistration(FD, Interest.WRITE))
    }

    @Test
    fun `the sweep tells a participant once however many registrations it holds`() = loopTest { loop ->
        // Once per participant, not once per registration. Stopping is a
        // lifecycle event: which entries the participant happened to hold
        // changes nothing about it, and the old per-registration double call
        // was an artifact of keying the notification on the ledger.
        val listener = RecordingListener()
        loop.addParticipant(listener)
        loop.registerCallback(FD, Interest.READ, listener)
        loop.registerCallback(FD, Interest.WRITE, listener)

        loop.failRemainingWaiters()

        assertEquals(1, listener.loopStopped, "told exactly once")
        assertEquals(emptyList(), listener.ready, "the sweep is not a readiness dispatch")
        assertEquals(emptyList(), listener.peerClosed, "and it is not a peer close")
    }

    @Test
    fun `the sweep tells a participant holding no registration at all`() = loopTest { loop ->
        // The connection this registry exists for. A paused connection holds no
        // registration -- its one-shot entry was consumed and the
        // back-pressured re-arm declined -- yet it is the one most likely to be
        // waiting on this loop, because keel's own flow control is what pauses
        // it. The ledger-keyed notification walked straight past it.
        val listener = RecordingListener()
        loop.addParticipant(listener)

        loop.failRemainingWaiters()

        assertEquals(1, listener.loopStopped, "a live participant is told even with an empty ledger")
    }

    @Test
    fun `removeParticipant ends the obligation to tell`() = loopTest { loop ->
        // The teardown half: a transport that closed cleanly must not be told
        // its loop stopped afterwards -- it is gone, and telling it would run
        // teardown callbacks on an object that already ran them.
        val listener = RecordingListener()
        loop.addParticipant(listener)
        loop.removeParticipant(listener)

        loop.failRemainingWaiters()

        assertEquals(0, listener.loopStopped, "a removed participant is not told")
    }

    @Test
    fun `a participant joining after the sweep is refused with a warning`() = loopTest { loop ->
        // Same closure, same shape as the ledger refusals: the registry is
        // emptied and closed in one critical section, so a late joiner is never
        // silently retained by a registry nothing reads again. Refusal, not a
        // throw -- every transport constructor calls this, and none of the
        // construction sites closes its fd on a constructor throw, so a throw
        // here would trade a reported dead channel for a descriptor leak.
        loop.failRemainingWaiters()

        val late = RecordingListener()
        loop.addParticipant(late)

        loop.failRemainingWaiters()

        assertEquals(0, late.loopStopped, "a refused participant is not retained and not told")
        assertTrue(
            loop.warnings.any { it.contains("addParticipant") },
            "and the refusal is reported, not silent: ${loop.warnings}",
        )
    }

    @Test
    fun `the sweep drains what a listener queued even with nothing stranded`() {
        // The drain used to be gated on stranded suspend waiters alone. A
        // pipeline-only loop strands none -- a write-only client with
        // readEnabled = false is exactly one, and is the case this sweep exists
        // for -- so anything the notification queued was dropped on the floor.
        // Teardown does queue: it cancels a flush continuation whose resume
        // lands on this very queue, and this is the last drain there will be.
        val loop = FakeLoop(runDispatchedInline = false)
        var queuedRan = false
        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    loop.dispatch(EmptyCoroutineContext, Runnable { queuedRan = true })
                }
            },
        )

        loop.failRemainingWaiters()

        assertTrue(queuedRan, "the sweep's own drain has to deliver it; nothing runs after")
    }

    @Test
    fun `a listener that throws does not strand the rest of the sweep`() {
        // Same backstop drainQueue puts around a dispatched task, for the same
        // reason: this runs user code, and one bad listener must not take the
        // others -- nor escape a pthread entry point with nothing above it.
        // Fails either way when unguarded: the throw either reaches this caller
        // or the healthy listener never hears, depending on iteration order.
        val loop = FakeLoop()
        val healthy = RecordingListener()
        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped(): Unit = throw IllegalStateException("boom")
            },
        )
        loop.addParticipant(healthy)

        loop.failRemainingWaiters()

        assertEquals(1, healthy.loopStopped, "the healthy participant is still told")
    }

    @Test
    fun `a negative fd keys its two interests apart in the ledger`() {
        // A negative fd sign-extends through the key's interest half, so without
        // the mask in registrationKey both interests hash to the same key and
        // the WRITE registration replaces the READ one.
        //
        // Probed by *identity*, deliberately. A presence probe
        // (hasCallbackRegistration) re-derives its key through the very
        // registrationKey being pinned, so under a broken mask both probes find
        // the one collided entry and pass -- measured: with the mask removed,
        // that form left all tests green. Identity survives the shared
        // derivation: under a collision the slot holds the *wrong* listener,
        // whichever key reaches it.
        val loop = FakeLoop()
        val readListener = RecordingListener()
        val writeListener = RecordingListener()
        loop.registerCallback(-1, Interest.READ, readListener)
        loop.registerCallback(-1, Interest.WRITE, writeListener)

        assertTrue(
            loop.popIfCurrent(loop.keyFor(-1, Interest.READ), readListener),
            "the READ slot must still hold the READ listener; a collision replaced it with WRITE's",
        )
        assertTrue(
            loop.popIfCurrent(loop.keyFor(-1, Interest.WRITE), writeListener),
            "and the WRITE slot its own",
        )
    }

    @Test
    fun `a participant may take the registration lock from onLoopStopped`() {
        // Why the notification runs outside withRegLock. The real path re-enters:
        // onLoopStopped -> onReadClosed -> close() -> teardownOnEventLoop ->
        // unregisterCallback -> withRegLock, on a mutex initialised with default
        // attributes, so it is not recursive. Moving the notification inside the
        // lock does not fail this test -- it hangs it, and every pipeline
        // transport with it, which is the point.
        val loop = FakeLoop()
        var reEntered = false
        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    loop.unregisterCallback(FD, Interest.WRITE)
                    reEntered = true
                }
            },
        )

        loop.failRemainingWaiters()

        assertTrue(reEntered, "the participant reached a lock-taking call and returned")
    }

    @Test
    fun `the sweep is a no-op when nothing is waiting`() = loopTest { loop ->
        loop.failRemainingWaiters()
        assertFalse(loop.waiters(FD, Interest.READ))
    }

    @Test
    fun `the sweep delivers the resume of a waiter dispatched on this loop`() = runBlocking {
        withTimeout(TEST_BUDGET) {
            // The case the sweep exists for, wired the way production wires it:
            // keel launches every connection handler on `channel.ioDispatcher`,
            // which is the EventLoop, so a connect() from there parks a
            // continuation whose resume comes back through this dispatch().
            // Cancelling only queues that resume -- if the loop stops without
            // draining again, the caller is cancelled and still parked.
            val loop = FakeLoop(runDispatchedInline = false)
            val waiters = CoroutineScope(coroutineContext + Job())
            try {
                val resumed = CompletableDeferred<Unit>()
                waiters.launch(loop) {
                    try {
                        suspendCancellableCoroutine { cont ->
                            val reg = loop.registerWaiter(FD, Interest.WRITE, cont)
                            cont.invokeOnCancellation { loop.unregister(reg) }
                        }
                        resumed.complete(Unit)
                    } catch (t: Throwable) {
                        resumed.completeExceptionally(t)
                    }
                }
                loop.drainDispatched() // start it; it registers and parks
                assertTrue(loop.waiters(FD, Interest.WRITE), "the waiter is in the ledger")

                loop.failRemainingWaiters()

                assertTrue(resumed.isCompleted, "the sweep must deliver the resume, not just queue it")
                assertSweptFailure(resumed)
                assertFalse(loop.waiters(FD, Interest.WRITE), "the ledger is emptied")
            } finally {
                waiters.cancel()
                // Before the join, and the reason this teardown differs from
                // loopTest's: nothing here runs dispatched work on its own, so
                // a waiter whose resume is still queued never completes, and a
                // NonCancellable join on it hangs instead of letting the failed
                // assertion be reported.
                loop.drainDispatched()
                withContext(NonCancellable) { waiters.coroutineContext.job.join() }
            }
        }
    }

    private companion object {
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
