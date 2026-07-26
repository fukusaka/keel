package io.github.fukusaka.keel.native.posix

import kotlinx.coroutines.CancellableContinuation
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
import kotlin.coroutines.CoroutineContext
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
 * The class has two abstract members, so a subclass can exercise every
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
        val dispatched = mutableListOf<Runnable>()

        /** Non-zero makes [submitArm] fail with this errno instead of arming. */
        var failArm: Int = 0

        override fun inEventLoop(): Boolean = onLoopThread

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched.add(block)
            if (runDispatchedInline) block.run()
        }

        fun drainDispatched() {
            val pending = dispatched.toList()
            dispatched.clear()
            for (block in pending) block.run()
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

        assertEquals(1, loop.dispatched.size, "an off-loop registration goes through dispatch")
        assertEquals(listOf(FD to Interest.READ), loop.armed)

        loop.onLoopThread = true
        suspendOn(loop, FD, Interest.WRITE).await()
        assertEquals(1, loop.dispatched.size, "an on-loop caller arms inline")
    }

    @Test
    fun `an off-loop arm does not reach the kernel until the loop drains it`() = loopTestWith(
        FakeLoop(onLoopThread = false, runDispatchedInline = false),
    ) { loop ->
        // The queuing the branch above only implies: nothing is armed while the
        // task sits in the queue, and the arm happens when the loop drains it.
        val w = suspendOn(loop, FD, Interest.READ).await()
        assertEquals(1, loop.dispatched.size)
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

        assertEquals(1, loop.dispatched.size, "an off-loop registerIf goes through dispatch")
        assertEquals(listOf(FD to Interest.READ), loop.armed)
        assertSame(reg, loop.popOne(FD, Interest.READ).first)
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
    }
}
