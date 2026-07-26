package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.collections.LongObjectMap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException

// Kept out of the KDoc because Dokka publishes that, and this is a note to
// whoever works on these two loops next: the bugs that made them expensive
// landed in the callback registry and its dispatch path, which this class does
// not move. Those still exist twice. The measurements are in the pull request,
// where they stay attached to the revision that took them.
/**
 * The registration ledger shared by the POSIX readiness engines — the epoll and
 * kqueue loops.
 *
 * The two engines kept near-identical copies of everything below. What differed
 * was the arming call, and the statement that prepared its arguments — kqueue
 * passed the [Interest] through, epoll first mapped it to an event mask. Both
 * collapse into [submitArm], whose epoll override does that mapping itself.
 *
 * **Only these two engines.** The other native loops are not close enough to
 * share it: io_uring keeps a registry too, but as a completion model its ledger
 * has a different shape, and the JDK-backed loop delegates its to a `Selector`.
 * `PosixReadiness` is in the name so it is not mistaken for a base every engine
 * extends — it still ends in `EventLoop`, because that is what it is part of.
 *
 * **What a subclass supplies**: [inEventLoop], and [submitArm] to issue whatever
 * the kernel interface wants (`EV_ADD`, `EPOLL_CTL_ADD` / `MOD`). Taking an
 * interest back stays with each engine, because the decision to do so is made in
 * its own dispatch path, which is not here. Everything about *when* to arm, the
 * FIFO chain of waiters per `(fd, interest)`, and the locking around it lives
 * here.
 *
 * **Thread safety**: the ledger is guarded by a `pthread_mutex_t`, and the
 * arming syscalls are funnelled to the loop thread here — by [handOff] for a
 * registration whose caller is waiting, by [submitOnLoop] for a callback arm — a
 * subclass supplies [inEventLoop] and [CoroutineDispatcher.dispatch] for it to
 * use, and should not write that branch itself.
 *
 * **Not an API**, and enforced as such: see [InternalPosixEventLoopApi]. This is
 * public only because the two loops that extend it live in other modules, where
 * `internal` does not reach. It was engine machinery before the split and still
 * is.
 */
@OptIn(ExperimentalForeignApi::class)
@InternalPosixEventLoopApi
abstract class AbstractPosixReadinessEventLoop : CoroutineDispatcher() {

    /**
     * Guards [registrations] — and, through [withRegLock], whatever else a
     * subclass chooses to put under it. Both engines guard their own callback
     * registry with this lock, and epoll its `fdEvents` map as well, so its
     * scope is wider than the one field named here: narrowing it, splitting it
     * per key, or destroying it earlier would unprotect state this class cannot
     * see.
     *
     * Separate from whatever protects the task queue: `dispatch` (any thread)
     * and `register` (coroutine thread) are independent hot paths that should
     * not block each other.
     *
     * Allocated from `nativeHeap` rather than from a subclass arena so that the
     * thing that creates it is the thing that frees it, in
     * [destroyRegistrationLock] — for a loop that reaches `close()`. A subclass
     * `init` that throws discards the instance without ever calling it, and this
     * slot leaks; the arena it replaced leaked the same way, so the failure path
     * is unchanged rather than fixed.
     *
     * `@PublishedApi internal` rather than private because [withRegLock] is
     * inline and reaches it. Inlining matters: the dispatch path takes this lock
     * per readiness event, so a lambda allocation there would be per-event.
     */
    @PublishedApi
    internal val regMutex: pthread_mutex_t = nativeHeap.alloc<pthread_mutex_t>().apply {
        val initRet = pthread_mutex_init(ptr, null)
        check(initRet == 0) { "pthread_mutex_init() failed: ${errnoMessage(initRet)}" }
    }

    /** Suspend-path waiters, keyed by [registrationKey]. */
    private val registrations = LongObjectMap<Registration>()

    /**
     * Claims the one teardown of [regMutex].
     *
     * A CAS rather than a flag because the hook it guards is published to
     * subclasses: the two engines happen to funnel `close()` through their own
     * compare-and-set first, but nothing in this class requires that, and a
     * plain `Boolean` would let two concurrent callers both pass the check and
     * free the same slot twice.
     */
    private val lockDestroyed = AtomicInt(0)

    /**
     * Runs [onLoop] on the loop thread, or [ifStopped] on the caller when the
     * loop has already run its final drain. Exactly one of the two runs.
     *
     * Separate from [submitOnLoop], which has no fallback: work queued to a
     * stopped loop is never drained, so anything whose caller is waiting for it
     * has to come through here. [ifStopped] runs off the loop, where this
     * class's ledger is moot and its mutex may already be freed, so it must not
     * touch either.
     */
    protected abstract fun handOff(onLoop: () -> Unit, ifStopped: () -> Unit)

    /** True when the caller already runs on this loop's thread. */
    abstract fun inEventLoop(): Boolean

    /**
     * Issues the arming syscall for [fd] + [interest] on the loop thread, and on
     * failure removes [reg] from the chain at [key] and resumes [cont] with the
     * error. Called only from the loop thread.
     */
    protected abstract fun submitArm(
        fd: Int,
        interest: Interest,
        key: Long,
        reg: Registration,
        cont: CancellableContinuation<Unit>,
    )

    /**
     * A pending I/O interest for a file descriptor.
     *
     * Multiple [Registration]s with the same `(fd, interest)` key form a
     * singly-linked FIFO chain via [next]. The chain head doubles as the map
     * entry; the head's [tail] field tracks the chain tail so append is O(1)
     * without per-key allocation. Non-head nodes ignore [tail].
     *
     * **Mutability**: [next] / [tail] are mutated only under the registration
     * mutex. No `@Volatile` because all access is lock-guarded.
     *
     * @param fd The file descriptor to watch.
     * @param interest What the waiter is waiting for.
     * @param continuation Resumed when the fd becomes ready.
     */
    class Registration internal constructor(
        val fd: Int,
        val interest: Interest,
        val continuation: CancellableContinuation<Unit>,
    ) {
        internal var next: Registration? = null
        internal var tail: Registration? = null
    }

    /** Encodes fd + interest into one key: fd in the low 32 bits, interest above. */
    protected fun registrationKey(fd: Int, interest: Interest): Long {
        return fd.toLong() or (interest.ordinal.toLong() shl 32)
    }

    /**
     * Runs [block] under the registration mutex.
     *
     * Moved as it was. The `pthread_mutex_lock` / `unlock` return codes are
     * still dropped here, which the project's error-handling rule counts as a
     * silent failure — but checking them turns a failed acquire into a throw on
     * a path that has no receiver, and deciding what a loop should do about
     * that is its own change rather than a side effect of moving the ledger.
     * Filed separately.
     *
     * `inline` so the critical section costs no lambda: the dispatch path takes
     * this lock per readiness event.
     */
    protected inline fun <T> withRegLock(block: () -> T): T {
        pthread_mutex_lock(regMutex.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(regMutex.ptr)
        }
    }

    /**
     * [register]s only if [stillWanted] holds, evaluated under the same lock
     * that [cancelAll] takes.
     *
     * `StreamServer.accept()` must decide "is my server still open?" and append
     * its waiter as one step: `close()` runs [cancelAll], and a registration
     * that lands after it is never resumed. Both sides already serialise on this
     * loop's registration lock, so the check belongs here rather than behind a
     * second mutex the server would have to own — and outlive.
     *
     * [stillWanted] runs **while the registration lock is held**, so it must be
     * a plain state read: taking another lock, or calling back into this loop,
     * can deadlock. `StreamServer` passes a volatile flag read.
     *
     * @return the [Registration], or `null` if [stillWanted] returned false.
     */
    fun registerIf(
        fd: Int,
        interest: Interest,
        cont: CancellableContinuation<Unit>,
        stillWanted: () -> Boolean,
    ): Registration? {
        val key = registrationKey(fd, interest)
        val newReg = Registration(fd, interest, cont)
        val appended = withRegLock {
            if (!stillWanted()) {
                false
            } else {
                appendRegistration(key, newReg)
                true
            }
        }
        if (!appended) return null
        handOff(
            onLoop = { submitArm(fd, interest, key, newReg, cont) },
            ifStopped = { cont.resumeWithException(loopClosed(fd, interest)) },
        )
        return newReg
    }

    /**
     * Registers interest in [fd] becoming ready for [interest], resuming [cont]
     * when it does.
     *
     * When the loop reports the fd ready, the head [Registration] of the
     * `(fd, interest)` chain is popped and its continuation is resumed with
     * [Unit]. The caller retries the I/O operation after being resumed.
     *
     * Concurrent callers for the same `(fd, interest)` form a FIFO chain. Each
     * readiness event pops one waiter; the level-triggered semantics of both
     * kernel interfaces then cascade-fire the next while the fd stays ready.
     * That is what makes the concurrent `accept()` pattern work — several
     * coroutines waiting on one server fd.
     *
     * @return this caller's own handle, to hand to [unregister] from
     *   `invokeOnCancellation`. Cancelling one waiter must not disturb the rest
     *   of the chain, so the handle identifies which one to drop.
     */
    protected fun register(fd: Int, interest: Interest, cont: CancellableContinuation<Unit>): Registration {
        val key = registrationKey(fd, interest)
        val newReg = Registration(fd, interest, cont)

        // Append BEFORE arming, to close the window where the kernel reports
        // readiness before the chain entry exists. The loop reads the chain
        // under this same lock, so the head is always found.
        withRegLock { appendRegistration(key, newReg) }

        handOff(
            onLoop = { submitArm(fd, interest, key, newReg, cont) },
            ifStopped = { cont.resumeWithException(loopClosed(fd, interest)) },
        )
        return newReg
    }

    /**
     * Runs [block] on the loop thread — inline when already there, queued
     * otherwise.
     *
     * Only that thread issues registration syscalls, so a disarm the loop
     * decides on — a stale interest being taken back from its dispatch path —
     * cannot reorder against an arm from a user thread for the same fd. This is
     * the `if (inEventLoop) inline else execute` shape libuv and Netty use, and
     * a subclass should route its own registration syscalls through it rather
     * than writing the branch again.
     *
     * `inline` because the on-loop branch is the read re-arm, which runs once
     * per readiness event per connection: a non-inline version would allocate a
     * closure there on every event, where the hand-written branch it replaces
     * allocated nothing. The off-loop branch still allocates its `Runnable`, as
     * it always did.
     *
     * Named apart from `LoopHandoff.runOnLoop` on purpose: that one takes an
     * `ifStopped` fallback and waits out the loop's final drain before choosing
     * between them. This one does not, and the difference is the hazard below.
     *
     * A caller arriving *before* the loop starts is fine: the task queues and
     * the loop drains it on its first iteration.
     *
     * **A caller arriving *after* the loop's final drain is not.** [block] is
     * queued to a queue nothing will drain again, so the syscall never happens.
     * That is survivable for a callback re-arm, where nothing is suspended
     * waiting on it, but not for a registration whose caller is parked — those
     * go through [handOff] instead, which has the fallback. A caller whose work
     * must not be silently dropped belongs there, not here.
     */
    protected inline fun submitOnLoop(crossinline block: () -> Unit) {
        if (inEventLoop()) {
            block()
        } else {
            dispatch(EmptyCoroutineContext, Runnable { block() })
        }
    }

    /**
     * Removes [reg] from its chain, leaving any sibling waiter on the same
     * `(fd, interest)` in place. A no-op if it is already gone — which is the
     * case after [cancelAll] has run.
     */
    fun unregister(reg: Registration) {
        val key = registrationKey(reg.fd, reg.interest)
        withRegLock { removeRegistration(key, reg) }
    }

    /**
     * Fails every waiter on `(fd, interest)` with [cause] and clears the chain.
     *
     * Used by server close: a waiter that never gets its readiness event would
     * otherwise hang. Runs under the registration lock so a concurrent
     * [registerIf] either lands before it — and is failed here — or sees
     * `stillWanted` return false.
     *
     * The kernel interest is left alone. Whoever owns [fd] closes it afterwards,
     * which is what actually stops readiness from being reported.
     *
     * Waiters that are already completed are skipped: a registration made after
     * the loop stopped was failed where it stood, because the fallback that
     * failed it runs off the loop and may not touch this chain.
     */
    fun cancelAll(fd: Int, interest: Interest, cause: Throwable) {
        val key = registrationKey(fd, interest)
        val toResume = mutableListOf<Registration>()
        withRegLock {
            var curr = registrations.remove(key)
            while (curr != null) {
                val next = curr.next
                curr.next = null
                curr.tail = null
                toResume.add(curr)
                curr = next
            }
        }
        // Skip waiters that are already done. A registration whose loop had
        // stopped was failed by the hand-off's fallback, which cannot take this
        // lock and so leaves the entry in the chain; resuming it again would
        // throw out of the close() that called this.
        for (reg in toResume) {
            if (reg.continuation.isActive) reg.continuation.resumeWithException(cause)
        }
    }

    /** Whether [reg] is still in the chain at [key]. Caller holds the lock. */
    protected fun isRegistered(key: Long, reg: Registration): Boolean {
        var curr = registrations[key]
        while (curr != null) {
            if (curr === reg) return true
            curr = curr.next
        }
        return false
    }

    /** Appends [reg] to the FIFO chain for [key]. Caller MUST hold [regMutex]. */
    protected fun appendRegistration(key: Long, reg: Registration) {
        val head = registrations[key]
        if (head == null) {
            registrations[key] = reg
        } else {
            val currentTail = head.tail ?: head
            currentTail.next = reg
            head.tail = reg
        }
    }

    /** Pops the head of the chain at [key], or null. Caller holds the lock. */
    protected fun popHeadRegistration(key: Long): Registration? {
        val head = registrations[key] ?: return null
        val next = head.next
        if (next == null) {
            registrations.remove(key)
        } else {
            // Transfer tail tracking to the new head: if the chain had exactly
            // two nodes, the new head IS the tail (set null); otherwise the new
            // head inherits the existing tail pointer.
            next.tail = if (head.tail === next) null else head.tail
            registrations[key] = next
        }
        head.next = null
        head.tail = null
        return head
    }

    /** Removes [reg] from the chain for [key] (search by identity). Caller MUST hold [regMutex]. */
    protected fun removeRegistration(key: Long, reg: Registration) {
        val head = registrations[key] ?: return
        if (head === reg) {
            val next = head.next
            if (next == null) {
                registrations.remove(key)
            } else {
                // Transfer tail tracking to the new head: if the chain had
                // exactly two nodes, the new head IS the tail (set null);
                // otherwise the new head inherits the existing tail pointer.
                next.tail = if (head.tail === next) null else head.tail
                registrations[key] = next
            }
            head.next = null
            head.tail = null
            return
        }
        var prev = head
        var curr = head.next
        while (curr != null) {
            if (curr === reg) {
                prev.next = curr.next
                // If we removed the tail, update head.tail to point at prev.
                if (head.tail === curr) head.tail = if (prev === head) null else prev
                curr.next = null
                return
            }
            prev = curr
            curr = curr.next
        }
    }

    /**
     * Destroys the registration mutex and frees it.
     *
     * The subclass calls this in its own teardown, after its loop thread is
     * joined and its fds are closed. That ends every use of the lock *from the
     * loop* and stops the kernel reporting readiness — it does not end every use
     * of the lock.
     *
     * **Known hazard, still open.** [unregister] runs on whichever thread
     * cancels, straight from an `invokeOnCancellation` handler, and takes this
     * lock. A waiter whose coroutine is cancelled after this call locks freed
     * memory. Nothing in the teardown sequence prevents it, because a
     * cancellation handler is neither loop work nor kernel readiness.
     *
     * Routing [unregister] through [handOff] does not fix it: the removal then
     * queues *behind* the arm it is supposed to cancel, so [submitArm]'s
     * "no longer in the chain" guard sees a stale chain and arms an fd the
     * handler has already closed. Measured, not reasoned about. A fix has to
     * keep the removal synchronous, which means keeping this mutex valid rather
     * than freeing it here.
     *
     * Idempotent, like the `close()` of the mutex this follows: whoever wins
     * [lockDestroyed] does the teardown and everyone else returns, so a second
     * call — sequential or concurrent — cannot destroy and free memory that is
     * already gone.
     *
     * The slot is freed whatever `pthread_mutex_destroy` returns, including
     * `EBUSY` — which means the mutex was still held or had a waiter, so that
     * holder resumes on freed memory. The arena this replaced did the same; the
     * decision now lives here, and it is the failed-destroy end of the hazard
     * described above rather than a separate one.
     *
     * @param onDestroyFailure invoked with the errno if `pthread_mutex_destroy`
     *   fails, so the subclass can log through its own logger. Called after the
     *   slot is freed, so it cannot leak it — but it must not throw: it runs
     *   partway through the subclass's `close()`, and an exception here skips
     *   whatever that method frees afterwards.
     */
    protected fun destroyRegistrationLock(onDestroyFailure: (Int) -> Unit) {
        if (!lockDestroyed.compareAndSet(0, 1)) return
        val destroyRet = pthread_mutex_destroy(regMutex.ptr)
        nativeHeap.free(regMutex)
        if (destroyRet != 0) onDestroyFailure(destroyRet)
    }

    /**
     * The failure a waiter gets when its loop stopped before the arm reached it.
     *
     * `CancellationException` rather than a plain failure: `accept()` runs in a
     * retry loop that rethrows cancellation and swallows everything else, so any
     * other type turns a stopped loop into a spin that re-registers every pass.
     * It also matches what `StreamServer.close()` already fails waiters with.
     */
    private fun loopClosed(fd: Int, interest: Interest) =
        CancellationException("EventLoop stopped before arming fd=$fd for $interest")

    /** Whether any waiter remains on `(fd, interest)`. Caller holds the lock. */
    protected fun hasWaiters(key: Long): Boolean = registrations[key] != null
}
