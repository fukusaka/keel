package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.buf.MpscQueue
import io.github.fukusaka.keel.collections.LongObjectMap
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
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
import platform.posix.pthread_self
import platform.posix.pthread_t
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resumeWithException

// Kept out of the KDoc because Dokka publishes that, and this is a note to
// whoever works on these two loops next: the bugs that made them expensive
// landed in the callback registry and its dispatch path. The registry is here
// now, reached through the keyed accessors below. The dispatch path that reads
// it still exists twice. The measurements are in the pull request, where they
// stay attached to the revision that took them.
/**
 * The registration ledgers shared by the POSIX readiness engines — the epoll and
 * kqueue loops. Two of them: the FIFO chain of suspend waiters, and the
 * pipeline-path callback listeners.
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
 * **What a subclass supplies**: [logger]; [inEventLoop]; [loopBody] (which
 * syscall waits and which errno is retriable); [wakeup] (pipe write against
 * eventfd write); [submitArm] to issue whatever the kernel interface wants
 * (`EV_ADD`, `EPOLL_CTL_ADD` / `MOD`); and the arming and dispatch of the
 * callback path, which reads this class's ledger through [putCallback] /
 * [popCallback] / [hasCallbackListener]. Taking an interest back stays with each
 * engine too, because the decision to do so is made in that dispatch path.
 *
 * [drainTasks] and [CoroutineDispatcher.dispatch] are *not* on that list any
 * more — both are concrete here, and overriding either would disable the drain's
 * re-entrancy guard or the queue itself. Everything about *when* to arm, the FIFO
 * chain of waiters per `(fd, interest)`, the task queue, the loop scaffolding and
 * the locking around all of it lives here.
 *
 * **Thread safety**: the ledger is guarded by a `pthread_mutex_t`, and the
 * arming syscalls are funnelled to the loop thread by [submitOnLoop] here — a
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
     * subclass chooses to put under it. It also guards [callbackRegistrations]
     * here, and epoll keeps its `fdEvents` map under it as well, so its scope is
     * wider than the one field named here: narrowing it, splitting it per key,
     * or destroying it earlier would unprotect both this class's second ledger
     * and state it cannot see.
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
     * Pipeline-path (non-suspend) listeners, keyed by [registrationKey].
     *
     * Kept apart from [registrations] rather than unified behind a sealed type:
     * a sealed wrapper would cost an allocation on the read re-arm, which runs
     * per readiness event. (Both ledgers are read by the same function —
     * readiness dispatch checks this one, then falls through to the other —
     * so it is the allocation that separates them, not the reader.) Taking an
     * [FdReadyListener] rather than a lambda lets each transport pass `this`,
     * so that path allocates nothing at all — the same shape
     * kotlinx.coroutines uses for `Job : DisposableHandle`.
     *
     * `private`, reached through [putCallback] / [popCallback] /
     * [hasCallbackListener], exactly as [registrations] is reached through its
     * own keyed accessors. A `protected` map would let any subclass mutate it
     * off the lock, and a [LongObjectMap] mutated concurrently with the loop
     * thread corrupts its open-addressing table.
     */
    private val callbackRegistrations = LongObjectMap<FdReadyListener>()

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
     * Claims the one entry into [loop], and the one drain in flight.
     *
     * [loop] is reachable from outside the two engines — the opt-in marker
     * limits who, not how often — and publishing the thread identity is the
     * first thing it does after this claim, so a second entry would re-point it
     * while the real loop thread still runs on the old one. [drainTasks] is separately re-entrant
     * from a task it is running: the outer call drains until the queue is empty,
     * so the inner one has nothing left to do and must not clear the shared
     * batch under it.
     */
    private val loopEntered = AtomicInt(0)

    /**
     * Claims the one drain in flight, so a task that re-enters [drainTasks] from
     * inside the batch it is running does not clear that batch under the outer
     * call. Set for the duration of the outer drain and cleared in its `finally`.
     */
    private val draining = AtomicInt(0)

    // Lock-free MPSC queue replaces pthread_mutex + MutableList for
    // dispatch hot path. CAS (~5-10ns) vs mutex lock/unlock (~50-100ns).
    private val taskQueue = MpscQueue<Runnable>()

    // Reusable scratch buffer for [drainTasks]. Kept as a field so the
    // EventLoop hot path does not allocate a new list each iteration.
    // Only accessed from the EventLoop thread (via [drainTasks]).
    private val drainBatch: MutableList<Runnable> = mutableListOf()

    // Off-loop -> loop hand-off, plus the two shutdown-progress flags it
    // gates on. Constructed here rather than by each engine: both wired it to
    // the same two members, which are now on this class.
    private val handoff = LoopHandoff(
        inEventLoop = ::inEventLoop,
        dispatchToLoop = { task -> dispatch(EmptyCoroutineContext, Runnable { task() }) },
    )

    /**
     * Hands [onLoop] to this EventLoop's thread; runs [ifStopped] on the caller
     * if the loop is already gone. Does not wait for either to finish — see
     * [LoopHandoff.runOnLoop] for why, and for what each block may touch.
     *
     * **Thread safety**: safe from any thread.
     */
    fun runOnLoop(onLoop: () -> Unit, ifStopped: () -> Unit = onLoop) {
        handoff.runOnLoop(onLoop, ifStopped)
    }

    /**
     * The loop's own thread, published by [loop] as the first thing it does
     * after claiming entry.
     *
     * `null` until then, which is what makes [inEventLoop] answer `false` for
     * a loop that was constructed but never started.
     *
     * `@Volatile` because the loop thread writes it and every other thread
     * reads it through [inEventLoop] to decide whether it may act directly or
     * must dispatch — a stale `null` there sends loop-thread work through the
     * queue, and a stale non-null sends off-loop work straight at state only
     * the loop may touch.
     */
    @kotlin.concurrent.Volatile
    protected var eventLoopThread: pthread_t? = null
        private set

    /**
     * Where this loop reports what it could not raise to a caller.
     *
     * Abstract rather than constructed here: each engine already takes one from
     * its config, and the transports on that engine read it through the loop.
     */
    abstract val logger: Logger

    /**
     * True when the caller already runs on this loop's thread.
     *
     * Left to each engine even though both write the same three lines:
     * `pthread_equal` is a per-target declaration and does not resolve in the
     * shared `nativeMain` metadata compilation. [eventLoopThread] is what they
     * compare against.
     *
     * It *can* be hoisted, and both routes were built and measured rather than
     * reasoned about. An `expect` here with an `actual` per platform source set
     * works on both hosts. A cinterop `keel_*` wrapper does **not**, which is the
     * less obvious half: `nativeMain` sees the *commonized* view of every native
     * target's interop library, and a Linux host has the Apple cinterop tasks
     * disabled outright, so a newly added wrapper is absent from that view there
     * — the shared metadata compile passes on macOS and fails on Linux.
     *
     * It stays per-engine because hoisting it bought nothing measurable: ten
     * interleaved runs per arm at saturation put the hoisted variant within noise
     * of this one, so the duplication is the cheaper of the two costs.
     */
    abstract fun inEventLoop(): Boolean

    /**
     * Runs one blocking wait on the kernel interface and dispatches what it
     * reports, until the loop is asked to stop.
     *
     * This is the whole of what differs between the two engines here: which
     * syscall waits, which errno values are retriable, and how a readiness
     * event maps onto [dispatchReady]. Everything around it — publishing the
     * thread, the final drain, the sweep, quiescence — is [loop].
     */
    protected abstract fun loopBody()

    /**
     * Nudges the kernel wait so [loopBody] returns promptly.
     *
     * Called only when work was queued from off the loop thread; a caller
     * already on it will reach the next drain without help.
     */
    protected abstract fun wakeup()

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
     * Runs everything queued on this loop's task queue, until it is empty.
     *
     * Loops rather than draining once: a resumed coroutine can queue more work
     * before this returns (a `read()` that suspends and is immediately made
     * ready again), and leaving that for the next kernel wait starves it.
     *
     * Each task is guarded on its own. A dispatched task that throws must not
     * kill the loop thread — every channel on this loop dies with it — nor skip
     * the rest of the batch. Coroutine tasks route their own body's exceptions
     * to their `Job` before reaching here; this is the backstop for the rest.
     */
    protected open fun drainTasks() {
        assertInEventLoop("drainTasks")
        if (!draining.compareAndSet(0, 1)) return
        try {
            drainQueue()
        } finally {
            draining.value = 0
        }
    }

    private fun drainQueue() {
        while (true) {
            drainBatch.clear()
            taskQueue.drain(drainBatch)
            if (drainBatch.isEmpty()) return
            // Index-based iteration avoids Iterator allocation on every drain cycle.
            for (i in 0 until drainBatch.size) {
                try {
                    drainBatch[i].run()
                } catch (t: Throwable) {
                    logger.warn(t) { "dispatched task threw on the EventLoop" }
                }
            }
        }
    }

    /** Whether anything is queued, without draining it. */
    protected fun hasTasksPending(): Boolean = taskQueue.isNotEmpty()

    /**
     * Queues [block] for the loop thread and wakes the loop if the caller is not
     * already on it.
     *
     * The contract a `launch(eventLoop) { }` caller reasons about: the block does
     * not run here, it runs on the next [drainTasks], which happens before the
     * kernel wait rather than after it.
     */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskQueue.offer(block)
        // Skip the wakeup when already on the loop thread: the next drain
        // happens before the kernel wait, and the wakeup is a syscall.
        if (!inEventLoop()) {
            wakeup()
        }
    }

    /**
     * Fails unless the caller is on the loop thread.
     *
     * Absolute rather than "null or matching". It used to return without
     * checking while the handle was unset, on the reasoning that only
     * single-threaded construction could get there. That reasoning was wrong:
     * `accept()` builds a transport — and registers its fd — on whatever thread
     * the caller is on, so a registration could reach a submit path from off-loop
     * during the window between `pthread_create` returning and [loop] publishing
     * the handle. Every submit path funnels through [submitOnLoop] now, so the
     * check can be absolute.
     *
     * That covers the submit paths, not every syscall an engine issues. Each
     * engine's constructor registers its own wakeup fd directly — before the
     * thread this would check against exists — and never reaches here.
     *
     * The engine name is composed only when the check fails.
     */
    protected fun assertInEventLoop(operation: String) {
        check(inEventLoop()) {
            "${this::class.simpleName}.$operation must run on the EventLoop thread"
        }
    }

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
        submitOnLoop { submitArm(fd, interest, key, newReg, cont) }
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

        submitOnLoop { submitArm(fd, interest, key, newReg, cont) }
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
     * **Known hazard, not introduced here.** A caller arriving *after* the loop
     * has run its final drain is not: [block] is queued to a queue nothing will
     * drain again, so an arming syscall silently never happens and the waiting
     * continuation is neither resumed nor failed. Both engines already hold a
     * `LoopHandoff`, which exists for exactly this window and takes an
     * `ifStopped` fallback, and since it moved here it is a field of this class
     * — [handoff], one member call away. What blocks the fix is not reach but
     * cost: this runs on the per-readiness-event path, and `runOnLoop` adds a
     * `CAS` and a claim object to every arm. Tracked with the matching teardown
     * hazard on [destroyRegistrationLock].
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
     */
    fun cancelAll(fd: Int, interest: Interest, cause: Throwable) {
        val key = registrationKey(fd, interest)
        val toResume = mutableListOf<Registration>()
        withRegLock { drainChainInto(registrations.remove(key), toResume) }
        for (reg in toResume) reg.continuation.resumeWithException(cause)
    }

    /**
     * Moves the chain starting at [head] into [into], detaching each node.
     *
     * One copy because both teardown paths need it and the detach ordering has
     * a silent failure mode: a node that keeps a stale [Registration.tail] makes
     * later appends land where nothing will pop them, and the waiter hangs
     * instead of failing. Caller holds the lock.
     */
    private fun drainChainInto(head: Registration?, into: MutableList<Registration>) {
        var curr = head
        while (curr != null) {
            val next = curr.next
            curr.next = null
            curr.tail = null
            into.add(curr)
            curr = next
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
     * **Known hazard, not introduced here.** [unregister] runs on whichever
     * thread cancels, straight from an `invokeOnCancellation` handler, and takes
     * this lock. A waiter whose coroutine is cancelled after this call locks
     * freed memory. Nothing in the teardown sequence prevents it, because a
     * cancellation handler is neither loop work nor kernel readiness. The
     * sibling hand-off helper records the same thing from the other side: work
     * that runs off the loop after it stops must not touch loop-owned state,
     * "their lock may already be destroyed by close".
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
     * Ends every waiter still in the ledger, because the loop is about to stop
     * reading it.
     *
     * [loop] calls this from its own `finally`, after the final drain and before
     * it publishes quiescence. Not the subclass: the ledger walked here is this
     * class's, and so is the rule for when a waiter can no longer be
     * served. It runs on the loop thread, so
     * taking the lock is legal, and the lock still exists: the only thing that
     * frees it is [destroyRegistrationLock], reachable only from a `close()`
     * that has completed its `pthread_join` — which cannot happen while this
     * thread is still here. (Both loops refuse that teardown when the join
     * reports `EDEADLK`, which is what a `close()` on this very thread produces.)
     *
     * **What it closes is one window, not the general case.** A registration
     * that arrives between the final drain and this call is ended here. One
     * that arrives *after* it still appends to the ledger and dispatches an arm
     * into a queue nobody drains, and stays parked — measured, not assumed.
     * Ending those means refusing the registration at its source, which is a
     * cost on the hot path and a separate decision; the attempt that took it is
     * recorded with what it cost.
     *
     * **Cancels rather than resuming with the failure.** Only a cancelled
     * continuation runs its `invokeOnCancellation` handler; a continuation
     * resumed with an exception does not. On the connect path that handler is
     * what closes the socket, so resuming here would end the wait and leak the
     * descriptor. Measured on this target rather than assumed.
     *
     * **And cancels with a `CancellationException`,** the same shape [cancelAll]
     * hands a waiter when its server closes. A cause that is *not* one completes
     * the waiter's coroutine exceptionally and so cancels its parent, which an
     * accept loop that ends quietly on server close should not suffer merely
     * because the loop stopped first.
     *
     * What that costs, deliberately: keel's own callers cannot tell this from
     * the caller's own cancel. `connectWithFallback` therefore stops trying
     * candidates, and a server's accept loop exits without logging.
     *
     * A distinct type on its own would change neither — both call sites match on
     * `CancellationException`, which any subclass satisfies. Making them act on
     * it means editing those call sites, and *that* is the part that must not
     * come back: with the fallback advancing past a stopped loop, the next
     * candidate registers on a loop `engine.close()` stopped as well, parks
     * forever, and can take the registration lock after `close()` freed it. It
     * was implemented, measured and withdrawn. Telling the two apart is only
     * safe once a stopped loop refuses registrations outright, which needs its
     * own change.
     *
     * **Drains afterwards, and that is not optional.** Cancelling runs the
     * handler synchronously but not the coroutine: the resume goes back through
     * [dispatch], and neither loop overrides `isDispatchNeeded`, so it lands on
     * this loop's own task queue even though the sweep is already on the loop
     * thread. Without the drain a waiter dispatched on the loop it was waiting
     * on stays parked — which is the hang this exists to end, and is what
     * `channel.ioDispatcher` gives every connection handler.
     *
     * What remains after this returns: a waiter registered by one of the
     * coroutines the drain resumed. That is a strictly narrower window than the
     * one being closed, and it needs a decision about how long a stopping loop
     * should keep serving new registrations, so it is tracked rather than
     * papered over with an unbounded sweep/drain alternation here.
     */
    protected fun failWaitersOnStoppedLoop() {
        val stranded = mutableListOf<Registration>()
        withRegLock {
            registrations.forEachValue { head -> drainChainInto(head, stranded) }
            registrations.clear()
        }
        // Outside the lock, as in cancelAll: a handler may re-enter this class.
        for (reg in stranded) {
            reg.continuation.cancel(
                CancellationException("EventLoop stopped before arming fd=${reg.fd} for ${reg.interest}"),
            )
        }
        // Deliver what those cancellations queued. See the KDoc: without this
        // the waiter is cancelled and still parked.
        if (stranded.isNotEmpty()) drainTasks()
    }

    /**
     * The loop thread's entry point: publish the thread, run [loopBody], and
     * take the loop apart in an order the rest of the class depends on.
     */
    fun loop() {
        if (!loopEntered.compareAndSet(0, 1)) {
            // Reported, not thrown. This runs as a pthread entry point with
            // nothing above it to catch, so throwing would end the process --
            // while the first, healthy loop is still serving every connection
            // on this engine. Returning skips the quiescence publish below, and
            // that is right: the entry that did claim the loop publishes it.
            // Same shape as destroyRegistrationLock's claim-once.
            logger.error { "${this::class.simpleName}.loop() entered twice; the second entry is ignored" }
            return
        }
        eventLoopThread = pthread_self()
        try {
            loopBody()
        } finally {
            // Order matters: publish "no longer draining" BEFORE the final
            // drain. A caller that offers a teardown and then reads a 0 here
            // knows its offer preceded this write, so the drain below is
            // guaranteed to see it; one that reads 1 takes the work back
            // itself. Draining first and publishing after would leave a gap
            // where an offer lands after the drain but before the flag, and
            // nobody runs it.
            handoff.markFinished()
            // markQuiescent() in its own finally: it is the only thing that
            // releases a runOnLoop caller from an unbounded spin, so a throw
            // anywhere below would live-lock whatever thread is closing a
            // server on this loop.
            try {
                // Nested so a throw from the drain cannot skip the sweep while
                // still publishing quiescence: that combination would strand
                // every waiter *and* free the lock their cancellation handlers
                // are about to take.
                try {
                    drainTasks()
                } finally {
                    // After the last drain, before quiescence is published:
                    // anything still in the ledger is waiting for an arm that
                    // can no longer issue. Ended here because this thread may
                    // take the lock and the lock still exists -- only close()
                    // frees it, and only after a join this thread has not let
                    // return. It drains again itself: cancelling a waiter
                    // queues its resume back onto taskQueue.
                    failWaitersOnStoppedLoop()
                }
            } finally {
                handoff.markQuiescent()
            }
        }
    }

    /** Whether any waiter remains on `(fd, interest)`. Caller holds the lock. */
    protected fun hasWaiters(key: Long): Boolean = registrations[key] != null

    /**
     * Whether a callback is registered on `key`. Caller holds the lock.
     *
     * Keyed per `(fd, interest)` rather than answered as a total, because a
     * total cannot tell a forgotten WRITE withdrawal from a READ one: both
     * transports of a loopback pair tear down together, so the total returns to
     * its baseline either way and the WRITE half can be deleted unnoticed.
     */
    protected fun hasCallbackListener(key: Long): Boolean = callbackRegistrations[key] != null

    /** Registers [listener] on `key`, replacing any predecessor. Caller holds the lock. */
    protected fun putCallback(key: Long, listener: FdReadyListener) {
        callbackRegistrations[key] = listener
    }

    /**
     * Withdraws the callback on `key` and returns it, or `null` if there was
     * none. Caller holds the lock.
     *
     * The kernel interest is left alone, the same caveat [cancelAll] carries for
     * the suspend ledger: whoever owns the fd disarms it, and until they do a
     * withdrawn-but-armed interest re-fires into readiness dispatch's no-handler
     * branch — a WARN per wake on kqueue's persistent filter, and per wake on
     * epoll because it is level-triggered.
     */
    protected fun popCallback(key: Long): FdReadyListener? = callbackRegistrations.remove(key)
}
