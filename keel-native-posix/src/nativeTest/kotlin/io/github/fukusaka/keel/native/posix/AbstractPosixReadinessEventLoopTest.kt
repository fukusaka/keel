package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
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
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

/**
 * Chain bookkeeping of [AbstractPosixReadinessEventLoop], driven directly.
 *
 * The class has three abstract members, so a subclass can exercise every
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
        val armed = mutableListOf<Pair<Int, Interest>>()

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

        override fun inEventLoop(): Boolean = onLoopThread

        /** No kernel to wait on: the loop body and its wakeup are inert here. */
        override fun loopBody() = Unit

        override fun wakeup() = Unit

        override val logger = NoopLoggerFactory.logger("FakeLoop")

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

        fun contains(fd: Int, interest: Interest, reg: Registration): Boolean =
            withRegLock { isRegistered(registrationKey(fd, interest), reg) }

        fun drain(fd: Int, interest: Interest): List<Registration> =
            generateSequence { popOne(fd, interest).first }.toList()

        /** Non-null after [destroy] if the mutex could not be destroyed. */
        var destroyErrno: Int? = null
            private set

        fun destroy() = destroyRegistrationLock { errno -> destroyErrno = errno }
    }

    /**
     * A subclass that leaves the base's own queue and drain in place.
     *
     * [FakeLoop] overrides both so a test can hold work between dispatch and
     * run, which means the base's `drainTasks` -- its re-entrancy claim, its
     * per-task backstop -- never executes there. This one exists to reach them.
     */
    private class RealQueueLoop(var onLoopThread: Boolean = true) : AbstractPosixReadinessEventLoop() {
        val logged = mutableListOf<Pair<LogLevel, String>>()

        /** How many times the base's own drain ran. Teardown must not repeat it. */
        var drainCalls: Int = 0
            private set

        /** Counts the wakeups [dispatch] issues, which only an off-loop caller earns. */
        var wakeups: Int = 0
            private set

        override fun inEventLoop(): Boolean = onLoopThread
        override fun loopBody() = Unit

        override fun wakeup() {
            wakeups++
        }

        override fun drainTasks() {
            drainCalls++
            super.drainTasks()
        }

        override fun submitArm(
            fd: Int,
            interest: Interest,
            key: Long,
            reg: Registration,
            cont: CancellableContinuation<Unit>,
        ) = Unit

        override val logger = object : Logger {
            override fun isLoggable(level: LogLevel) = true
            override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
                logged.add(level to message.toString())
            }
        }

        /** [drainTasks] is protected on the base; this is the subclass reaching it. */
        fun drain() = drainTasks()

        /** Non-null after [destroy] if the mutex could not be destroyed. */
        var destroyErrno: Int? = null
            private set

        fun destroy() = destroyRegistrationLock { errno -> destroyErrno = errno }
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
     * Runs [block] with a scope whose waiters are cancelled, and whose lock is
     * destroyed, at the end — in that order.
     *
     * Most waiters here are deliberately never resumed; that is the state under
     * test. They are launched into a scope of their own rather than the
     * timeout's, because `withTimeout` waits for its children and would report
     * a parked waiter as a timeout instead of letting the test assert on it.
     *
     * The order matters and is the same one production gets wrong: [suspendOn]
     * installs the real `invokeOnCancellation { unregister(reg) }`, so a waiter
     * cancelled after the lock is freed takes it on freed memory. Cancelling
     * first keeps this suite honest about that. Destroying here rather than in
     * each test also means the lock is freed when an assertion fails.
     */
    private fun loopTest(block: suspend CoroutineScope.(FakeLoop) -> Unit) = loopTestWith(FakeLoop(), block)

    private fun loopTestWith(loop: FakeLoop, block: suspend CoroutineScope.(FakeLoop) -> Unit) = runBlocking {
        withTimeout(TEST_BUDGET) {
            val waiters = CoroutineScope(coroutineContext + Job())
            try {
                waiters.block(loop)
            } finally {
                // join, not just cancel: suspendOn installs the production
                // invokeOnCancellation { unregister(reg) }, which takes the lock
                // destroy() is about to free. Cancel handlers happen to run
                // inline today because every waiter is parked at its suspension
                // point — joining makes the ordering structural instead.
                //
                // NonCancellable because this finally also runs on the timeout
                // path, where the enclosing coroutine is already cancelled and a
                // bare join() throws at once — measured: the destroy() below is
                // then skipped and the mutex leaks on exactly the run that
                // failed.
                waiters.cancel()
                withContext(NonCancellable) { waiters.coroutineContext.job.join() }
                loop.destroy()
            }
            // Reported after the block, never from the finally: a teardown
            // failure must not replace the assertion that actually failed —
            // which is what throwing from `finally` would do.
            val destroyErrno = loop.destroyErrno
            if (destroyErrno != null) fail("pthread_mutex_destroy() failed: errno=$destroyErrno")
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
    fun `destroying the registration lock twice frees it once`() = loopTest { loop ->
        // The one behaviour this class adds rather than moves. Without the
        // claim, a second caller frees a slot the allocator has already taken
        // back; with only a plain flag, two concurrent callers both pass.
        loop.destroy()
        loop.destroy()
        assertNull(loop.destroyErrno, "the first teardown succeeded and the second did nothing")
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
        // on the loop thread, where the lock is still valid, because close() has
        // not yet joined and destroyed it.
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
                loop.destroy()
            }
            // Reported after the block for the same reason loopTest does it:
            // a teardown failure must not replace the assertion that failed.
            // This test hand-rolls its scope, and is the only sweep test whose
            // waiter is not wrapped in a catch -- the shape most likely to
            // leave a cancellation handler mid-flight when the mutex is freed.
            val destroyErrno = loop.destroyErrno
            if (destroyErrno != null) fail("pthread_mutex_destroy() failed: errno=$destroyErrno")
        }
    }

    // --- the two guards on the base's own queue and loop ---

    @Test
    fun `a task that drains again does not lose the batch it re-entered`() {
        // drainTasks is re-entrant from a task it is running. The outer call
        // already drains until the queue is empty, so the inner one has nothing
        // left to do -- and must not clear the shared batch under the iteration.
        val loop = RealQueueLoop()
        try {
            val ran = mutableListOf<String>()
            loop.dispatch(EmptyCoroutineContext, Runnable { ran.add("first") })
            loop.dispatch(EmptyCoroutineContext, Runnable { ran.add("re-enters"); loop.drain() })
            loop.dispatch(EmptyCoroutineContext, Runnable { ran.add("third") })

            loop.drain()

            assertEquals(listOf("first", "re-enters", "third"), ran, "every task runs exactly once")
        } finally {
            loop.destroy()
        }
    }

    @Test
    fun `a task that throws does not stop the rest of its batch`() {
        val loop = RealQueueLoop()
        try {
            var laterRan = false
            loop.dispatch(EmptyCoroutineContext, Runnable { throw IllegalStateException("boom") })
            loop.dispatch(EmptyCoroutineContext, Runnable { laterRan = true })

            loop.drain()

            assertTrue(laterRan, "the task queued after the throwing one must still run")
            assertTrue(loop.logged.any { it.first == LogLevel.WARN }, "the throw must be reported: ${loop.logged}")
        } finally {
            loop.destroy()
        }
    }

    @Test
    fun `entering the loop a second time is refused without throwing`() {
        // loop() runs as a pthread entry point with nothing above it to catch,
        // so a second entry is reported and ignored rather than thrown -- and it
        // must not re-point the thread identity the whole class reads.
        val loop = RealQueueLoop()
        try {
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
            // says the second entry returned.
            assertEquals(1, loop.drainCalls, "teardown ran once, not twice")
            assertNull(loop.destroyErrno, "and left the registration lock destroyable")
        } finally {
            loop.destroy()
        }
    }

    @Test
    fun `dispatch wakes the loop only when the caller is off it`() {
        // The branch is one line and its failure mode is a stall, not a log:
        // a cross-thread dispatch that skips the wakeup leaves the task queued
        // while the kernel wait sits on its deadline, or forever. Neither of
        // this file's other subclasses can reach it -- one answers on-loop
        // unconditionally, the other overrides dispatch entirely.
        val loop = RealQueueLoop(onLoopThread = true)
        try {
            loop.dispatch(EmptyCoroutineContext, Runnable { })
            assertEquals(0, loop.wakeups, "an on-loop caller drains before the next wait")

            loop.onLoopThread = false
            loop.dispatch(EmptyCoroutineContext, Runnable { })
            assertEquals(1, loop.wakeups, "an off-loop caller has to interrupt the wait")
        } finally {
            loop.destroy()
        }
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
                loop.destroy()
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
