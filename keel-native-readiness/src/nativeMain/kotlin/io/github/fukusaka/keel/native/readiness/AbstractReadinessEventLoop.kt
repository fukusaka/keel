package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.MpscQueue
import io.github.fukusaka.keel.collections.LongObjectMap
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.DeadlineScheduler
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_trylock
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_self
import platform.posix.pthread_t
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.TimeSource

// Kept out of the KDoc because Dokka publishes that, and this is a note to
// whoever works on these two loops next: the callback registry and its dispatch
// path are here now too, so a bug in either is fixed once. What is still written
// twice is the lifecycle -- start, close and the arena -- the syscall wrappers
// each kernel interface needs, and two things neither of those covers: the
// `ReadinessSuspendRegister` member, which both engines implement identically by
// delegating to this class's own `awaitWritableOwningFd`, and `inEventLoop`,
// which both write as the same three lines over `pthread_equal`. The writev scratch is here now; what
// each engine still repeats is the one call that gives it back. The measurements are in
// the pull requests, where they stay attached to the revisions that took them.
/**
 * The loop the POSIX readiness engines — epoll and kqueue — run on, and what it
 * reads: its task queue, both registration ledgers (the FIFO chain of suspend
 * waiters and the pipeline-path callback listeners), the participant registry
 * the stop notification is keyed on, and the readiness dispatch over them.
 *
 * The two engines kept near-identical copies of everything below. What differed
 * was the arming call, and the statement that prepared its arguments — kqueue
 * passed the [Interest] through, epoll first mapped it to an event mask. Those
 * collapse into two hooks, [submitArm] for the suspend path and
 * [submitArmCallback] for the pipeline one, whose epoll overrides do the mapping
 * themselves. They are two rather than one because the masks differ: READ is
 * `EPOLLIN` for a suspend waiter and `EPOLLIN or EPOLLRDHUP` for a callback —
 * the callback path is the one that has to hear a graceful FIN. Taking the
 * interest back does *not* mirror the split: epoll's `removeInterest` clears
 * both bits for READ whichever hook set them.
 *
 * **Only these two engines.** The other native loops are not close enough to
 * share it: io_uring tracks its registrations too, but as a completion model
 * its ledger has a different shape, and the JDK-backed loop delegates its to a
 * `Selector`.
 * `Readiness` is in the name so it is not mistaken for a base every engine
 * extends — it still ends in `EventLoop`, because that is what it is part of.
 *
 * **What a subclass supplies** is the kernel interface — [loopBody] (which
 * syscall waits and which errno is retriable); [wakeup] (pipe write against
 * eventfd write); [submitArm] and [submitArmCallback] to issue an arm for the
 * suspend and pipeline paths; and [removeInterest] to take one back — plus two
 * that are not engine knowledge at all: [logger], which each engine already
 * takes from its config, and [inEventLoop], whose few lines are identical in
 * both and stay there for the reasons its own KDoc sets out — both routes were
 * built and measured. The thread they compare against is published here, by
 * [loop].
 *
 * *When* to arm, when to take back, the FIFO chain of waiters per
 * `(fd, interest)`, the callback registry, the task queue, the loop scaffolding
 * and the locking around all of it live here.
 *
 * [drainTasks] and [CoroutineDispatcher.dispatch] are *not* on that list — both
 * are concrete here, and a subclass that replaces them gives up the drain's
 * re-entrancy guard or the queue itself. Neither engine does. [drainTasks] is
 * nonetheless `open`, for the test fake that has to hold work between dispatch
 * and run; the suite carries a second fake that leaves both in place so the
 * real ones are covered.
 *
 * **Thread safety**: the ledger is guarded by a `pthread_mutex_t`, and both
 * paths get their arming syscalls onto the loop thread — the suspend path
 * through [submitOnLoop], the callback path by spelling the same fork out —
 * on top of [dispatch] and the task queue behind it. A subclass answers
 * [inEventLoop] and should not write that branch itself.
 *
 * **Not an API**, and enforced as such: see [InternalReadinessEngineApi]. This is
 * public only because the two loops that extend it live in other modules, where
 * `internal` does not reach. It was engine machinery before the split and still
 * is.
 */
@OptIn(ExperimentalForeignApi::class)
@InternalReadinessEngineApi
// The class sits just under detekt's LargeClass threshold -- measured, two
// logical lines of headroom -- so no suppression is carried. It did trip the
// rule mid-branch and was suppressed; what bought the room back was dropping
// submitArm's dead parameter, not the Registration extraction, which recovered
// less than the work here had spent. The consolidation itself is the design --
// both engines' loop state and its dispatch, ledger and teardown paths live
// here so a bug in any of them is fixed once, and the file header carries that
// account. When an addition does trip the rule, the answer is the next
// extraction, which is tracked design work, not a suppression.
abstract class AbstractReadinessEventLoop :
    CoroutineDispatcher(),
    ReadinessEventLoopLifecycle,
    ReadinessSuspendRegister {

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
     * Allocated from `nativeHeap`, and **never destroyed or freed** — the slot
     * stays valid for as long as the process runs.
     *
     * That is deliberate, and it is the only shape that is safe. [unregister]
     * runs on whichever thread cancels, straight out of an
     * `invokeOnCancellation` handler, and takes this lock; so does every
     * refused registration on a stopped loop, since refusing cancels the
     * caller's continuation and that runs the same handler. A cancellation can
     * arrive at any time after `close()` — user code calling `read()` on a
     * stopped engine produces one — so there is no point at which "nobody will
     * take this lock again" becomes true. Freeing it at teardown made that a
     * use-after-free; ordering the teardown behind the cancellations already
     * pending does not help, because the arrivals are unbounded in time.
     *
     * The cost is one `pthread_mutex_t` per EventLoop instance, never
     * reclaimed: bounded by how many loops the process creates, and paid only
     * by a process that keeps building and closing engines. A destroyed lock
     * would still have to be memory nobody frees to be safe to touch, so
     * destroying without freeing buys nothing but an `EINVAL` on the next
     * acquire.
     *
     * A subclass `init` that throws discards the instance without this slot
     * ever being reachable; that was already true and is unchanged.
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
     * `private`, and so is every keyed accessor on it. A subclass reaches it
     * only through [isCallbackRegistered] / [popCallbackIfCurrent], which its
     * arm hook uses and which are scoped to one listener, and through
     * [hasCallbackFor], which takes the lock itself. A `protected` map would let
     * any subclass mutate it off the lock, and [LongObjectMap] states its own
     * contract: not thread-safe, synchronise externally.
     */
    private val callbackRegistrations = LongObjectMap<FdReadyListener>()

    /**
     * Connection-lifetime participants of this loop, told once each when it
     * stops. Identity-keyed: a [LoopParticipant] joins when its channel attaches and
     * leaves in its teardown, and none of the in-tree participants overrides
     * `equals`, so the default identity semantics are the contract.
     *
     * Beside the ledgers rather than derived from them, because the ledgers
     * cannot answer "who is alive": a paused connection holds no registration —
     * its one-shot entry was consumed and the back-pressured `onReadable`
     * declined to re-arm — yet it is exactly the connection most likely to be
     * waiting on this loop. Keying the stop notification on the ledger walked
     * past it.
     *
     * `private`, guarded by [regMutex] like both ledgers, and closed by the same
     * [ledgersClosed] write that closes them.
     */
    private val participants = LinkedHashSet<LoopParticipant>()

    /**
     * Set once [failWaitersOnStoppedLoop] has swept, after which both ledgers
     * and the participant registry refuse to take anything new. Caller holds
     * the lock.
     *
     * The sweep empties the ledgers and the registry, and tells every
     * participant it found there — but that alone does not make it a *fixed
     * point*: an append landing afterwards — a participant re-registering out of
     * `onLoopStopped`, or a task the sweep's own final drain runs — went into a
     * map nothing reads again. That entry is
     * never dispatched, never swept, and holds its transport, channel and
     * pipeline graph for as long as this stopped loop object lives. Refusing is
     * also the honest answer: the loop cannot arm it, so parking on it is a hang
     * dressed as a wait.
     *
     * Written inside the sweep's own critical section, so "swept" and "closed"
     * are one atomic step and nothing can slip between them. Read by all three
     * append paths — both ledgers and the registry — which already run under
     * this lock, so the check is a plain field
     * read on a lock the caller is holding anyway: no new atomic, and nothing on
     * the per-readiness-event path.
     */
    private var ledgersClosed: Boolean = false

    /**
     * How the registration lock failed to acquire or release, or `null` while
     * it has not. Read by each engine's `loopBody` through [regLockBroken] so
     * the loop ends the way a poll fatal ends it, and again through
     * [regLockFailureDetail] for the record that says so.
     *
     * One value rather than a flag beside a description, because two would
     * have to be written in an order — the description first, since the flag
     * is what a reader synchronises on — and an order stated is an order that
     * can be got wrong. It was, while this was two values. Here the question
     * "has it failed" and the answer "with what" are the same value, so there
     * is no order left to keep.
     */
    private val regLockFailure = AtomicReference<String?>(null)

    /**
     * How the registration lock failed — for the body writing the record that
     * says this is why the loop is ending.
     *
     * The stand-in is unreachable: a caller reaches this only behind
     * [regLockBroken], which is true of exactly the states in which this is
     * non-null. It is here because a getter cannot say that in its type.
     */
    protected fun regLockFailureDetail(): String =
        regLockFailure.value ?: "the registration lock stopped being exclusive"

    /**
     * Set when a *release* failed, so the mutex is still held by the thread
     * that reported it. Distinct from [regLockFailure] because only this case
     * makes re-taking the lock a deadlock rather than merely unguarded.
     */
    private val regLockStuck = AtomicInt(0)

    /**
     * Claims the terminal sequence, and with it the ledgers.
     *
     * Two takers, never both: [loop] on its way in, and [finishWithoutRunning]
     * closing a loop that has no thread. Whoever takes it publishes the thread
     * identity next, so a second taker would re-point that identity while the
     * first still runs on the old one — which is what this refuses.
     *
     * Taken through [claimLoopTermination]; [loop] is reachable from outside
     * the two engines (the opt-in marker limits who, not how often), so the
     * refusal is not merely defensive.
     *
     * Only that. The drain's own re-entrancy is [draining]'s, below.
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
     * if the loop is already gone. On a live loop this returns before the work
     * runs; a caller landing mid-shutdown blocks until the loop is quiet — see
     * [LoopHandoff.runOnLoop] for the wait's shape, for what each block may
     * touch, and for when a caller should bound that wait with
     * [waitBudgetMicros] rather than take the default.
     *
     * **Thread safety**: safe from any thread.
     *
     * @return which block ran, and whether the wait was cut short to get
     *   there; see [HandoffOutcome].
     */
    fun runOnLoop(
        onLoop: () -> Unit,
        ifStopped: () -> Unit = onLoop,
        waitBudgetMicros: Long = LoopHandoff.WAIT_UNBOUNDED,
    ): HandoffOutcome = handoff.runOnLoop(onLoop, ifStopped, waitBudgetMicros)

    /**
     * Whether this loop has stopped for good — it will run nothing more, so a
     * [dispatch] is accepted by the queue and never drained.
     *
     * For callers that must decide *before* handing work over, where
     * [runOnLoop] is the wrong shape: it waits out a loop that is mid-shutdown,
     * and a caller inside `suspendCancellableCoroutine` cannot afford to block
     * its thread there. Reading this instead leaves the mid-shutdown window on
     * the dispatch path, which is correct — the final drain still picks that up.
     *
     * **Thread safety**: safe from any thread.
     */
    fun isStopped(): Boolean = handoff.isQuiescent()

    /**
     * Whether this loop has stopped polling — it may still run already-queued
     * work in its final drain, but it will never wait for readiness again.
     *
     * For a caller about to **park** on something only a future event can
     * complete. [isStopped] is the wrong question there: it is still `false`
     * throughout the final drain and the stop sweep, so a task running in
     * that window would read "not stopped" and park on a loop that will never
     * wake it. The sweep ends the waiters it can see, and a continuation
     * stored *after* the sweep has walked the participants is not one of them.
     *
     * **Thread safety**: safe from any thread.
     */
    fun isFinishing(): Boolean = handoff.isFinished()

    /**
     * The thread that holds this loop's termination claim, published as the
     * first thing either claimant does after taking it.
     *
     * Usually the loop's own pthread. It is the closing thread instead when
     * [finishWithoutRunning] takes a loop apart that never had one — and that
     * is the point: everything downstream reads this to ask "may I act on this
     * loop's state directly", and while the claim is held the answer for that
     * thread is yes. It is not a thread handle and nothing joins on it.
     *
     * `null` before either claimant publishes it, which is what makes
     * [inEventLoop] answer `false` for a loop that has been constructed and
     * neither started nor closed.
     *
     * `@Volatile` because the loop thread writes it and every other thread
     * reads it through [inEventLoop] to decide whether it may act directly or
     * must dispatch — a stale `null` there sends loop-thread work through the
     * queue, and a stale non-null sends off-loop work straight at state only
     * the loop may touch.
     *
     * **Cleared when the terminal sequence ends**, as the last thing the
     * claiming thread does with it. On the loop's own thread that is also its
     * death; on a closer's it is not, and clearing matters there for a second
     * reason — otherwise that thread goes on answering [inEventLoop] `true`
     * for a loop it has finished taking apart. A
     * `pthread_t` is only unique among live threads, so holding the value past
     * the thread's lifetime would let an unrelated thread that inherits the id
     * answer [inEventLoop] with `true` — the second of the two failures above,
     * arriving long after the loop is gone. `null` from that point on is not a
     * loss of information: there is no loop thread to be on.
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
     * Engine-wide flush coalescing, from the engine config.
     *
     * Read by the transports on this loop to decide whether `flush()` sends
     * inline or schedules a tick. Both engines took it as a constructor
     * parameter with the same default; it is declared here so a transport can
     * ask the loop it has rather than the engine's subtype.
     *
     * Defaulted rather than abstract: this base has test doubles that drive the
     * ledger and the sweep without any transport on them, and making them
     * declare a flush policy they never consult would be noise.
     */
    open val flushCoalescing: Boolean get() = true

    /**
     * This loop's own buffer allocator, a child of the engine's.
     *
     * Per loop so pooling needs no lock: only this thread allocates from it.
     * Both engines took it as a constructor parameter; it is declared here so
     * the servers that hand a connection to a loop can read it off the base.
     * Defaulted rather than abstract, like the flush policy above and for the
     * same reason: this base's test doubles never allocate.
     */
    open val allocator: BufferAllocator get() = DefaultAllocator

    /**
     * Effective per-connection read buffer size for connections on this loop
     * ([io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]).
     */
    open val readBufferSize: Int get() = IoTransport.DEFAULT_READ_BUFFER_SIZE

    /**
     * Engine-wide idle (no-progress) timeout for connections on this loop
     * ([io.github.fukusaka.keel.core.IoEngineConfig.idleTimeoutMillis]).
     */
    open val idleTimeoutMillis: Long get() = 0

    /**
     * Whether this loop still holds a callback for [fd] + [interest].
     *
     * A probe for the engines' tests, which ask it about a connection they have
     * just torn down. Behind the opt-in marker like the rest of this surface.
     */
    @InternalReadinessEngineApi
    open fun hasCallbackRegistration(fd: Int, interest: Interest): Boolean = hasCallbackFor(fd, interest)

    /**
     * Drops [fd] from whatever this loop keeps for it beyond the kernel's own
     * interest set. Called from a connection's teardown.
     *
     * Nothing to do on a loop whose registration dies with the descriptor —
     * a kqueue filter does. A loop that keeps its own table has to be told:
     * left behind, that entry makes a recycled fd number look already
     * registered, so the `ctl` for the next connection on that number is
     * skipped and it is watched by nobody.
     */
    @InternalReadinessEngineApi
    open fun cleanupFd(fd: Int) {}

    /** Monotonic origin for [nowMillis]; per loop, so the marks never cross loops. */
    private val timeOrigin = TimeSource.Monotonic.markNow()

    /** Milliseconds since this loop was constructed. */
    protected fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    /**
     * Per-EventLoop deadline timer backing the transport idle (no-progress)
     * timeout.
     *
     * Confined to this loop's thread: transports on it schedule, touch and
     * cancel idle deadlines through this, the loop body drives its wait timeout
     * from [DeadlineScheduler.nextDeadlineMillis], and fires what is due via
     * [DeadlineScheduler.expireDue].
     *
     * Built on first use, not in this constructor: [logger] is the subclass's,
     * and reading it from here runs before the subclass has assigned it.
     */
    val deadlineScheduler: DeadlineScheduler by lazy { DeadlineScheduler(::nowMillis, logger) }

    /**
     * The `iovec` scratch a gather write on this loop fills in.
     *
     * Held here because it is shared by every transport the loop serves, and
     * built with the loop so an ordinary gather finds it ready rather than
     * allocating one. What owns it, and what makes releasing it safe, is
     * written on the type.
     */
    internal val writevScratch = WritevScratch()

    /**
     * Releases the gather scratch. Called from each engine's teardown.
     *
     * The engines cannot name [WritevScratch] — it is this module's — so the
     * obligation reaches them through here.
     */
    protected fun freeWritevScratch() {
        writevScratch.free()
    }

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
     * Issues the arming syscall for [fd] + [interest] on the loop thread, and
     * hands a failure to [failUnarmedWaiter]. Called only from the loop thread.
     *
     * An override does not resume [reg]'s waiter itself: the failure half is
     * the part that does not differ, so it lives on the base, and what a
     * refused resumption then costs is decided in one place. That also means
     * an override never resumes the waiter *unconditionally* — a dispatcher
     * that refuses leaves it suspended, with whatever it owned released.
     */
    protected abstract fun submitArm(fd: Int, interest: Interest, key: Long, reg: Registration)

    /**
     * Takes [fd]'s [interest] back from the kernel.
     *
     * The two engines spell this differently — `EV_DELETE` against
     * `EPOLL_CTL_MOD` / `DEL` on a recomputed mask — and epoll additionally has
     * to drop the fd entirely once nothing is left, so the decision stays with
     * each of them. [dispatchReady] only decides *when*.
     */
    protected abstract fun removeInterest(fd: Int, interest: Interest)

    /**
     * Drops whatever this loop records about [fd]'s interests, for a caller that
     * is about to close it. No syscall: closing a descriptor takes it out of the
     * kernel's interest list on its own, and issuing a disarm from a thread that
     * is not the loop's would reorder against the loop's own arms.
     *
     * Distinct from [removeInterest], which takes one interest back from a
     * descriptor that goes on living. This is the whole record, and only because
     * the descriptor is ending.
     *
     * Default is nothing, which is right for an engine that keeps no such
     * record: kqueue's filters live in the kernel and end with the descriptor.
     * epoll has to mirror the mask in user space to compute `EPOLL_CTL_MOD`, and
     * a mirror outliving its descriptor is worse than no mirror — the next
     * socket to be handed that number looks already-armed, so its arm is skipped
     * and its waiter parks with nothing watching it.
     */
    protected open fun forgetInterests(fd: Int) {
        // Nothing to forget by default; see the KDoc.
    }

    /**
     * Arms [fd] + [interest] for the pipeline path, on the loop thread.
     *
     * The callback counterpart of [submitArm]: same thread, same one differing
     * expression. What an implementation owns is the arming syscall and nothing
     * else — [key] and [listener] are carried so the failure path does not
     * recompute or re-look-up either.
     *
     * **Refusing an arm whose listener is gone** is [registerCallback]'s, on the
     * branch that needs it: only a *queued* arm can outlive a withdrawal, and
     * only that branch pays for the check.
     *
     * **A failed arm** goes to [withdrawFailedCallbackArm], which is where the
     * two copies of this drifted apart before — epoll discarded the errno while
     * kqueue withdrew and logged. An implementation passes the errno and the name
     * of the syscall that produced it, **and returns what the withdrawal
     * returns**: the failure for the caller whose retry just vanished, or null
     * when the arm took (or the failed listener was already superseded).
     */
    protected abstract fun submitArmCallback(
        fd: Int,
        interest: Interest,
        key: Long,
        listener: FdReadyListener,
    ): Throwable?

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
     * Queues [block] for the loop thread and wakes the loop if the caller is
     * not already on it — and the loop is not yet quiescent. After quiescence
     * there is no next drain: the task stays queued, unread, as bounded
     * retention, and no wakeup is written (the fd may already be released).
     *
     * The contract a `launch(eventLoop) { }` caller reasons about while the
     * loop lives: the block does not run here, it runs on the next
     * [drainTasks], which happens before the kernel wait rather than after it.
     */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskQueue.offer(block)
        // Skip the wakeup when already on the loop thread: the next drain
        // happens before the kernel wait, and the wakeup is a syscall. Skip
        // it too once the loop is quiescent — its close may already have
        // released the wakeup fd, and the kernel may have re-handed the
        // number, so the write would be a stray byte in someone else's
        // descriptor. The offer stays: bounded retention on a queue nothing
        // reads, which is the best a dispatch to a dead loop can do.
        if (!inEventLoop() && !handoff.isQuiescent()) {
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
     * the handle. Every submit path funnels to the loop thread now, so the
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
     * Encodes fd + interest into one key: fd in the low 32 bits, interest above.
     *
     * The fd is masked rather than widened. A negative one sign-extends through
     * the interest half, colliding READ with WRITE. Every non-negative fd — which
     * is every fd a checked `accept` / `socket` / `connect` returns — keys exactly
     * as before.
     */
    protected fun registrationKey(fd: Int, interest: Interest): Long {
        return (fd.toLong() and FD_MASK) or (interest.ordinal.toLong() shl KEY_INTEREST_SHIFT)
    }

    /**
     * Runs [block] under the registration mutex.
     *
     * Both return codes are checked, and a failure goes to
     * [reportRegLockFailure] — which ends the loop rather than throwing, for
     * the reasons written there.
     *
     * **[block] still runs after a failed acquire.** There is nothing better to
     * return from an inline helper whose callers all expect a value, and the
     * loop is on its way down either way. **The release does not**: unlocking a
     * mutex this thread does not hold is undefined, and an implementation that
     * does not check ownership would release the section another thread is
     * inside — turning one thread's failed acquire into a loss of exclusion for
     * everyone. So the unlock is conditional on the acquire having succeeded.
     *
     * `inline` so the critical section costs no lambda: the dispatch path takes
     * this lock per readiness event.
     */
    protected inline fun <T> withRegLock(block: () -> T): T {
        val lockRet = pthread_mutex_lock(regMutex.ptr)
        if (lockRet != 0) reportRegLockFailure("lock", lockRet, stillHeld = false)
        try {
            return block()
        } finally {
            if (lockRet == 0) {
                val unlockRet = pthread_mutex_unlock(regMutex.ptr)
                if (unlockRet != 0) reportRegLockFailure("unlock", unlockRet, stillHeld = true)
            }
        }
    }

    /**
     * Records that the registration lock failed, and tells the loop to stop.
     *
     * A failure here means the ledgers are no longer exclusive — a failed
     * acquire runs [withRegLock]'s block anyway (there is nothing better to
     * return from an inline helper whose callers all expect a value), and a
     * failed release leaves the lock held forever. Neither state is one the
     * loop can keep serving connections in: the next arm could be issued for a
     * key another thread is mid-way through changing.
     *
     * So this takes the shape the poll fatals already use — `logger.error` and
     * end the loop thread, rather than a throw. A throw from here would leave
     * the ledgers exactly as they were (this is the first statement of the
     * readiness dispatch), and a level-triggered fd would report ready again
     * immediately; guarding that with a catch turns it into a spin. Ending the
     * loop instead runs the same teardown any other fatal takes.
     *
     * **Unreachable in practice, and that is the point.** Three calls reach
     * here: one from a failed acquire and two from a failed release. The
     * acquire takes a default `pthread_mutex_t` (no error-checking attribute)
     * that is never destroyed, so `EDEADLK` cannot arise and `EINVAL` needs the
     * slot itself to have been invalidated. Neither release is ever issued for
     * a mutex this thread does not hold — one runs only under a successful
     * lock, the other only under a successful `trylock` — which is the case a
     * default mutex defines, and it returns zero. What this replaces is a
     * discarded return value: the failure was not impossible before, it was
     * silent.
     *
     * **Public, under the opt-in, so a test can reach the reporting entry point
     * the syscall cannot.** Nothing in the tree can drive `withRegLock` into
     * calling this, for the reason above. What a test *can* do is call it, and
     * then check that the loop it belongs to stops — which is the half of the
     * wiring that would otherwise be held by nothing.
     *
     * The opt-in is not a formality here — the marker is on this member, not
     * only on the class. A class-level marker gates *naming*
     * [AbstractReadinessEventLoop], which a caller reaching an inherited
     * member through a concrete engine type never does — such a caller reaches
     * [loop] with no opt-in at all. Strip the marker from this declaration and
     * it is callable from any module that depends on an engine.
     *
     * It is not `inline` that forces the change: `@PublishedApi internal`
     * already satisfies that, which is what [regMutex] still is. What `internal`
     * does not do is cross a Gradle module boundary, and the tests are on the
     * other side of one.
     */
    @InternalReadinessEngineApi
    fun reportRegLockFailure(operation: String, errno: Int, stillHeld: Boolean) {
        // Stored rather than recorded here: which call failed and with what
        // errno is known only at this point, and whether it is the reason the
        // loop is stopping is knowable only in the body, which reads this and
        // decides. Storing it is also what tells the body to stop, so there is
        // no second write to order against this one. First writer wins, for
        // the same reason the record does -- a second failure is what follows
        // from the ledgers no longer being exclusive.
        regLockFailure.compareAndSet(
            null,
            "pthread_mutex_$operation() failed on the registration lock: ${errnoMessage(errno)}",
        )
        // A failed release leaves this thread holding the mutex; a failed
        // acquire does not. The teardown has to tell them apart: it can still
        // run its sweep in the second case, but re-taking a mutex this thread
        // already holds would deadlock the loop inside its own teardown.
        if (stillHeld) regLockStuck.value = 1
        logger.error {
            "pthread_mutex_$operation() failed on the registration lock: ${errnoMessage(errno)} — " +
                "the ledgers are no longer exclusive, so this EventLoop is stopping"
        }
        // Same guard [dispatch] takes, and for the same reason: this runs on
        // whichever thread took the lock, which after teardown is any thread at
        // all, and a quiescent loop's wakeup fd is closed — its number possibly
        // re-handed. A loop that is already quiescent has nothing to be woken
        // for either.
        if (!handoff.isQuiescent()) wakeup()
    }

    /**
     * Whether the registration lock has failed, in which case the loop must
     * stop. Read by each engine's `loopBody` beside its own poll fatal.
     */
    protected fun regLockBroken(): Boolean = regLockFailure.value != null

    /**
     * Whether the registration lock can be acquired right now — it takes it and
     * puts it straight back.
     *
     * A probe, in the same family as [hasCallbackFor]: it answers "is this lock
     * free", which is the question a teardown used to answer implicitly by
     * seeing whether `pthread_mutex_destroy` reported `EBUSY`. Nothing destroys
     * the lock any more, so that signal has to be asked for.
     */
    protected fun regLockFree(): Boolean {
        val rc = pthread_mutex_trylock(regMutex.ptr)
        if (rc != 0) return false
        val unlockRet = pthread_mutex_unlock(regMutex.ptr)
        if (unlockRet != 0) {
            reportRegLockFailure("unlock", unlockRet, stillHeld = true)
            return false // took it and could not put it back: not free
        }
        return true
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
     * @return the [Registration], or `null` when it was **not appended** —
     *   either [stillWanted] returned false, or the loop has stopped and closed
     *   its ledgers. A caller cannot tell those apart and must not describe the
     *   result as one of them: the server that answers `null` with a
     *   `CancellationException` names both causes in its message for that reason.
     * @param onUndeliverable As [register]'s: a waiter that owns something for
     *   the duration of its wait releases it here when the loop cannot deliver
     *   its answer. Today's one production caller — the accept path — owns
     *   nothing and passes nothing, but the parameter exists so an owning
     *   waiter arriving through this entry does not silently re-inherit the
     *   leak the other entry closed.
     */
    fun registerIf(
        fd: Int,
        interest: Interest,
        cont: CancellableContinuation<Unit>,
        onUndeliverable: (() -> Unit)? = null,
        stillWanted: () -> Boolean,
    ): Registration? {
        val key = registrationKey(fd, interest)
        val newReg = Registration(fd, interest, cont, onUndeliverable)
        val appended = withRegLock {
            // A closed ledger declines the same way a caller that stopped
            // wanting it does. This path already reports "not appended" as
            // `null` and its caller already resumes the continuation itself, so
            // it needs no second failure mode.
            if (ledgersClosed || !stillWanted()) {
                false
            } else {
                appendRegistration(key, newReg)
                true
            }
        }
        if (!appended) return null
        submitOnLoop { submitArm(fd, interest, key, newReg) }
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
     * [onUndeliverable] is for a caller that owns something for the duration of
     * its wait: it runs when the loop cannot hand this waiter its answer. See
     * [Registration.onUndeliverable].
     *
     * @return this caller's own handle, to hand to [unregister] from
     *   `invokeOnCancellation`. Cancelling one waiter must not disturb the rest
     *   of the chain, so the handle identifies which one to drop.
     */
    protected fun register(
        fd: Int,
        interest: Interest,
        cont: CancellableContinuation<Unit>,
        onUndeliverable: (() -> Unit)? = null,
    ): Registration {
        val key = registrationKey(fd, interest)
        val newReg = Registration(fd, interest, cont, onUndeliverable)

        // Append BEFORE arming, to close the window where the kernel reports
        // readiness before the chain entry exists. The loop reads the chain
        // under this same lock, so the head is always found.
        val appended = withRegLock {
            if (ledgersClosed) {
                false
            } else {
                appendRegistration(key, newReg)
                true
            }
        }
        if (!appended) {
            // Same outcome, and the same cause type, as arriving a moment
            // earlier and being swept: a plain CancellationException. Giving
            // this its own type was built and measured for the sweep in #1004
            // and withdrawn -- telling the two apart only helps a caller that
            // then advances past a stopped loop, which is the hang that change
            // produced. The returned [Registration] was never appended, so the
            // caller's `invokeOnCancellation { unregister(reg) }` is a no-op and
            // the rest of its handler -- closing the fd it owns -- still runs.
            // Through the hand-off helper like every other way this class
            // ends a waiter, though for the one in-tree caller the guard is
            // inert: it registers inside its `suspendCancellableCoroutine`
            // block, before suspension, so this cancel consults no dispatcher
            // and no handler is installed yet -- measured, nothing here can
            // throw for that caller. The contract is for the caller shape
            // prose cannot forbid: a continuation that has already suspended
            // meets its own dispatcher here, a refusal must not escape into
            // the registering frame, and the hook -- which no other code will
            // ever consult for a waiter refused entry -- must run.
            deliverOrRelease(newReg, "cancelling the refused waiter for, while its caller carries on,") {
                val cause = CancellationException("EventLoop stopped before fd=$fd could register for $interest")
                it.continuation.cancel(cause)
            }
            return newReg
        }

        submitOnLoop { submitArm(fd, interest, key, newReg) }
        return newReg
    }

    /**
     * Suspends until [fd] is write-ready, **owning [fd] for the duration**: if
     * the wait ends any way other than readiness, this closes it.
     *
     * The shared `connect()` path waits here on `EINPROGRESS`. The
     * descriptor exists but belongs to nobody yet — no transport has been built
     * around it — so an abnormal exit that leaves it open leaks it for the life
     * of the process, with no handle left to close it by.
     *
     * Cancellation was already covered by the handler below. **Failure was not**,
     * and it is the reachable one: the [submitArm] path fails this continuation
     * when the arming syscall fails, and `invokeOnCancellation` does
     * not run for an exceptional resume — so before this function existed, an
     * `epoll_ctl` / `kevent` that failed with `ENFILE` left the connect socket
     * open and unreferenced. When the loop's own thread is the caller, the arm
     * runs inline inside [register] and fails *before* the handler is even
     * installed.
     *
     * **The one-shot is the mechanism, not a belief about ordering.** The four
     * claimants are reached by different means — one by cancellation, one by a
     * thrown value, one by the loop finding this waiter undeliverable, and the
     * normal return — and nothing here orders them against each other. Rather
     * than argue that a run of one excludes the others, the compare-and-set
     * makes that true: whichever arrives first takes the descriptor, and the
     * rest return without touching it. The first three release what they take;
     * the normal return keeps it, which is the point of claiming — a caller
     * that resumed cannot be handed a number the loop has already closed.
     * Closing a descriptor twice is not a harmless repeat once the kernel has
     * handed the number to somebody else.
     *
     * **The third is the loop's, not this frame's.** An answer the loop cannot
     * hand over — a dispatcher that refuses the resumption, whether the answer
     * is a readiness, a failed arm, the stop sweep's cancellation, or the
     * refusal of a registration the closed ledgers would not take — never
     * reaches this frame, and the waiter is already out of the ledger. On the
     * sweep the cancellation handler below has run first, a cancelled
     * continuation running its handlers before the resumption its dispatcher
     * then refuses; on the other routes neither handler runs. Either way the
     * hook passed to [register] completes the release — it shares the one
     * claim below with the handler, the `catch` and the normal return, and
     * that claim admits exactly one.
     *
     * [unregister] runs on the two that end in this frame, and is a no-op when the
     * node is already gone — which it is on the [submitArm] path, which removes
     * it before resuming. Keeping it on the failure path is defensive: the only
     * other way to fail a waiter is [cancelAll], whose one caller passes
     * `Interest.READ` on a server fd, so nothing reaches here with a node still
     * in the chain today. It stays because a stale node makes a later append
     * land where nothing pops it, and the check costs a lock on a path taken
     * once per failed connect.
     *
     * **The release runs on the loop, and that ordering is the point.** All
     * three releasing endings can be reached from a thread that is not the
     * loop's, while the loop still has this fd's arm queued or in flight —
     * [submitArm] checks
     * that the waiter is registered before arming, so a release landing between
     * the check and the arm closes the descriptor and then lets the arm run.
     * What follows is the recycled-fd hazard: an arm against a number the kernel
     * may already have handed to somebody else. On epoll the check and the arm
     * take the registration lock separately, which widens that window, and its
     * user-space mask is then recorded for a descriptor nothing will ever clear
     * it for; kqueue takes the lock once and keeps no such mask. Handing the release to the loop puts it after that arm.
     * `StreamServer.close()` reaches the same conclusion for the listening fd
     * and says so; this is the same shape for the connect fd.
     *
     * So the release is *claimed* here and *performed* there: the caller sees
     * the wait end before the descriptor is necessarily gone. Nothing on this
     * path needs it back — the transport that would own it is built only after
     * a normal return.
     *
     * [forgetInterests] is not the same clean-up as [unregister]. That one
     * drops this waiter from the ledger of coroutines to resume; this one drops
     * whatever the loop records about the descriptor itself, which on epoll is
     * the mask it uses to decide whether an arm needs a syscall at all. It is
     * skipped when the loop has stopped, for the reason its own teardown gives:
     * that bookkeeping belongs to a loop that will never read it again.
     *
     * The counter, the captured handle and the release hook are three
     * allocations per in-progress connect — not a hot path (at most once per outbound connection, and only
     * when the connect did not complete immediately), and nothing here runs per
     * readiness event.
     */
    protected suspend fun awaitWritableOwningFd(fd: Int, logger: Logger) {
        val released = AtomicInt(0)
        var reg: Registration? = null
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val own = register(fd, Interest.WRITE, cont) {
                    // The third ending: the loop had this waiter's answer --
                    // a readiness, a failed arm, the stop sweep's
                    // cancellation, or the refusal of a registration the
                    // closed ledgers would not take -- and its dispatcher
                    // refused to take it. Nothing resumes into the `catch`; on
                    // the sweep and on that refusal the handler below has
                    // already run, on the other routes it never does; and the
                    // registration is already gone -- so without this the
                    // descriptor can stay open with no handle left to close it
                    // by, which is the leak this whole function exists to
                    // prevent. The one-shot claim admits exactly one of this,
                    // the handler, the `catch` and the normal return below,
                    // whichever comes first.
                    releaseOwnedFd(fd, logger, released, "connect answer undeliverable")
                }
                reg = own
                cont.invokeOnCancellation {
                    unregister(own)
                    releaseOwnedFd(fd, logger, released, "connect cancellation")
                }
            }
            // The fourth claimant: a delivery the loop judged refused can
            // still have taken effect -- a dispatcher may enqueue the
            // resumption and then throw, and nothing in its contract forbids
            // it. Then the hook releases while this frame resumes normally,
            // and without a claim here the caller would build a transport
            // over a descriptor the loop has closed and the kernel may have
            // re-handed. So the normal return claims the same one-shot: if
            // the hook won, the descriptor is gone and this wait must say so
            // rather than hand back a number that is no longer this
            // connect's.
            if (!released.compareAndSet(0, 1)) {
                error("connect wait for fd=$fd was answered, but the loop had released it as undeliverable")
            }
        } catch (t: Throwable) {
            reg?.let { unregister(it) }
            releaseOwnedFd(fd, logger, released, "connect wait failed")
            throw t
        }
    }

    /**
     * Claims [fd] for release once, then hands the release to the loop.
     *
     * The claim is a compare-and-set rather than an argument about which of
     * [awaitWritableOwningFd]'s endings can follow the other: they are
     * reached by different means — a cancellation, a thrown value (whether the
     * wait's own or an arm that failed), an answer the loop could not hand
     * over, and the normal return, which claims without releasing so a
     * delivery the loop wrongly judged refused cannot leave its caller
     * holding a closed descriptor — and nothing orders them. Closing a descriptor twice stops being a harmless repeat the
     * moment the kernel has handed the number to somebody else.
     *
     * On the loop because of what may still be queued for this fd; see
     * [awaitWritableOwningFd]. When the loop is already gone there is nothing
     * to order against and nothing left to forget, so the fallback only closes.
     */
    private fun releaseOwnedFd(fd: Int, logger: Logger, released: AtomicInt, context: String) {
        if (!released.compareAndSet(0, 1)) return
        handoff.runOnLoop(
            onLoop = {
                forgetInterests(fd)
                closeFdSafely(fd, logger, context)
            },
            ifStopped = {
                closeFdSafely(fd, logger, context)
            },
        )
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
     * `ifStopped` fallback and, unless its caller hands in a wait budget, waits
     * out the loop's final drain before choosing between them. This one does
     * not, and the difference is the hazard below.
     *
     * A caller arriving *before* the loop starts is fine: the task queues and
     * the loop drains it on its first iteration.
     *
     * **No `wakeup()` follows an arm, deliberately.** An off-loop caller's task
     * goes through [dispatch], which does the wake itself; an on-loop caller is
     * already inside the iteration that will drain it, and the engine-init path
     * has no wait to interrupt. Adding one here would put a syscall on the
     * per-readiness-event re-arm, which is why [registerCallback] spells the
     * fork out rather than calling [submitOnLoop].
     *
     * **Known hazard, not introduced here.** A caller arriving *after* the loop
     * has run its final drain is not: [block] is queued to a queue nothing will
     * drain again, so an arming syscall silently never happens and the waiting
     * continuation is neither resumed nor failed. [handoff] is right here and
     * takes an `ifStopped` fallback for exactly this window, so reach is not what
     * blocks the fix. Cost is: `runOnLoop` adds a `CAS` and a claim object to
     * every submission — a figure taken from reading [LoopHandoff.runOnLoop],
     * not from a benchmark. **This class's own callers now narrow it, and do not
     * close it.** [register], [registerIf] and [registerCallback] all consult
     * `ledgersClosed`, but each releases the registration lock before
     * submitting, so a caller that passed the check can still be overtaken: the
     * sweep runs between the two, ends the waiter it just appended, and the
     * submission lands in a queue nothing drains. What that costs is no longer a
     * hang — the waiter is already cancelled — but the `Runnable` and everything
     * it captures stay reachable for the loop object's lifetime. Closing it
     * needs the submission itself inside the lock, which puts a syscall there.
     * The general shape is unchanged for a subclass routing its own submission
     * through this function.
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
        // Guarded per element, like the stop sweep's identical loop: these go
        // through each waiter's own dispatcher, and one that refuses must not
        // strand the waiters behind it in this local list -- they left the
        // ledger at the drain above, so nothing else can find them -- nor
        // escape into the caller, a server close that still has a descriptor
        // to release.
        for (reg in toResume) {
            deliverOrRelease(reg, "failing the waiter for, while the server closes,") {
                it.continuation.resumeWithException(cause)
            }
        }
    }

    /**
     * Removes [reg] from the chain at [key] and fails its waiter with
     * [failure], through [deliverOrRelease].
     *
     * Here rather than in each engine's `submitArm` because it is the half of
     * that override that does not differ — and the callback twin,
     * [withdrawFailedCallbackArm], is here for exactly that reason: its two
     * engine copies drifted apart once before it was hoisted. One copy also
     * means one report string — it lives only here — which the guard test
     * pins for every engine at once instead of pinning a fixture's imitation.
     *
     * The resume goes through the waiter's own dispatcher and a refusal must
     * not escape the loop; on the connect path the waiter owns its descriptor,
     * whose own release paths cannot run on that ending — the hand-off
     * helper's KDoc carries the full account.
     */
    protected fun failUnarmedWaiter(key: Long, reg: Registration, failure: IllegalStateException) {
        withRegLock { removeRegistration(key, reg) }
        deliverOrRelease(reg, "failing the waiter for, while the loop goes on arming others,") {
            it.continuation.resumeWithException(failure)
        }
    }

    /**
     * Hands [delivery] to a waiter that has already left the ledger, and
     * releases whatever it owned if that fails.
     *
     * The five places this loop answers a waiter — readiness, a server
     * closing, the stop sweep, an arm that failed, and a registration the
     * closed ledgers refuse — all deliver through the waiter's *own*
     * dispatcher, which this loop does not control: one backed by a pool shut
     * down under it refuses the work. (An `Unconfined` waiter runs its
     * continuation inline in this frame, which is why the delivery is made
     * outside every lock — but a throw from that body belongs to the
     * coroutine's own handler and never arrives here.) The loop cannot retry (the same call
     * meets the same refusal) and must not put the waiter back (the
     * continuation may already hold the answer, and a level-triggered interest
     * would then re-fire into `Already resumed` every turn). What is left is to
     * report it and to run whatever the waiter registered for exactly this
     * ending — see [Registration.onUndeliverable].
     *
     * The delivery and the release are each guarded, and what an escape would
     * have cost differs by the site that called in. The
     * readiness dispatch runs directly under the loop body and the `pthread`
     * entry, which catches nothing: an escape ends the process. So does the
     * stop sweep when it runs in `loop()`'s `finally` — though a loop closed
     * without ever running sweeps on the closing thread instead, where an
     * escape would fail the `close()` mid-teardown. The server close and the
     * failed arm that was *dispatched* reach here inside a guarded frame — a
     * task, or whatever loop frame ran the close inline — whose guard swallows
     * the throw: no death, but the waiter is lost and what it owns leaks, with
     * nothing naming either. An arm a loop-thread [register] issued inline
     * instead surfaces in that caller's frame, which is the shape of the fifth.
     * The fifth caller is not a loop frame at all: a
     * registration refused by closed ledgers is answered in the registering
     * caller's own `suspendCancellableCoroutine` block, where an escape would
     * surface as that caller's failure. Every one of those endings is why each
     * hand-off routes here rather than only the fatal ones.
     *
     * [what] names the delivery *and* what carries on without it — the sites
     * differ there, and an operator reading "the loop continues" from the stop
     * sweep would be reading the one thing that is not true of it.
     *
     * `protected` rather than `private` so an engine-side hand-off can reach
     * it directly; nothing outside this class does today — the engines' arm
     * failure goes through [failUnarmedWaiter], which is here. Not `inline` — a public-API inline body cannot reach the non-public
     * state this needs. The readiness dispatch calls this once per ready fd,
     * so [delivery] takes the [Registration] as its argument: the readiness
     * lambda captures nothing and compiles to a singleton, and the per-event
     * cost of the guard is one call, not one allocation. The sweep's lambda
     * came out capture-free too; the three that do capture — the server
     * close, the failed arm, and the refused registration — are reached once
     * per close, per failed arm and per refused registration, where an
     * allocation is not worth shaping the code around.
     */
    @Suppress("TooGenericExceptionCaught")
    protected fun deliverOrRelease(reg: Registration, what: String, delivery: (Registration) -> Unit) {
        try {
            delivery(reg)
        } catch (deliveryFailure: Throwable) {
            logger.error(deliveryFailure) {
                "$what fd=${reg.fd} ${reg.interest} threw; it is no longer registered"
            }
            try {
                reg.onUndeliverable?.invoke()
            } catch (releaseFailure: Throwable) {
                logger.error(releaseFailure) {
                    "releasing what the undeliverable waiter for fd=${reg.fd} owned threw as well"
                }
            }
        }
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
    private fun appendRegistration(key: Long, reg: Registration) {
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
     * Ends everything still in **both** ledgers, because the loop is about to
     * stop reading them — every suspend waiter is cancelled and the callback
     * entries are dropped — and tells every [LoopParticipant] in the registry,
     * once each, through [LoopParticipant.onLoopStopped]. Told per participant
     * and not per registration: a paused connection holds no registration at all
     * and is exactly the one most likely to be waiting, and a two-interest
     * connection is one connection, not two.
     *
     * The bulk clear of the callback ledger arrived with the sweep's callback
     * half; the registry and the per-participant keying arrived after it.
     * Individual entries always came out — [unregisterCallback] on teardown,
     * [dispatchReady] on every event — but nothing emptied the map in bulk when
     * the loop stopped, so a listener still registered at that moment kept its
     * transport — and the channel and pipeline graph behind it — reachable for as
     * long as the loop object lived.
     *
     * [terminate] calls this after the final drain and before it publishes
     * quiescence, on whichever thread holds the termination claim — the loop's
     * own, or a closer taking apart a loop that never ran. Not the subclass:
     * the ledger walked here is this class's, and so is the rule for when a
     * waiter can no longer be served. The claimant is the only thread that may
     * walk it, so taking the lock is legal — and
     * the lock is valid here as it is everywhere, because nothing ever frees
     * it (see [regMutex]).
     *
     * **What it closes is one window, not the general case.** A registration
     * that arrives between the final drain and this call is ended here. One
     * that arrives *after* it is now **refused** rather than parked: the last
     * act of the critical section that empties the ledgers is to close them, so
     * everything this function does afterwards — the notifications and the final
     * drain — already runs against closed ledgers. What that does not reach is a
     * caller already past the ledger and on its way into
     * `withContext(ioDispatcher)`, whose resume is itself queued to the dead
     * queue.
     *
     * **Cancels rather than resuming with the failure.** Only a cancelled
     * continuation runs its `invokeOnCancellation` handler; a continuation
     * resumed with an exception does not. Measured on this target rather than
     * assumed. That asymmetry used to be the whole reason for the choice — the
     * connect path's handler was the only thing closing the socket, so resuming
     * here leaked it. [awaitWritableOwningFd] now releases on every one of its
     * endings, so that consequence is gone and this stands on the reason below
     * instead. The
     * asymmetry itself is unchanged, and a handler is still the only thing a
     * caller can hang clean-up on without wrapping the wait.
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
     * candidate registers on a loop `engine.close()` stopped as well and parks
     * forever. (It could also take the registration lock after `close()` freed
     * it — that half is gone: nothing frees it now.) It was implemented,
     * measured and withdrawn. Telling the two apart is only
     * safe once a stopped loop refuses registrations outright — which it now
     * does, so the prerequisite is met and the decision is open again. It stays
     * unmade here because the fallback's call sites are what would have to
     * change, and that is not this function's to decide.
     *
     * **Drains afterwards, and that is not optional.** Cancelling runs the
     * handler synchronously but not the coroutine: the resume goes back through
     * [dispatch], and neither loop overrides `isDispatchNeeded`, so it lands on
     * this loop's own task queue even though the sweep is already on the loop
     * thread. Without the drain a waiter dispatched on the loop it was waiting
     * on stays parked — which is the hang this exists to end, and is what
     * `channel.ioDispatcher` gives every connection handler.
     *
     * A waiter registered by one of the coroutines that drain resumes is **not**
     * what remains — the ledgers were closed before the drain ran, so it is
     * refused and ended like any other late arrival. What remains is narrower
     * still: a caller already past the ledger check and inside
     * `withContext(ioDispatcher)`, whose resume is queued to this same dead
     * queue. Reaching that one means gating before the park rather than at the
     * ledger, which is its own decision and is tracked.
     */
    protected fun failWaitersOnStoppedLoop() {
        // A *release* failure leaves the mutex held by the thread that issued
        // it, so taking it again here would deadlock the loop inside its own
        // teardown — and with it the quiescence publish and the closer's join.
        // That case has to skip the sweep, owning waiters' releases included:
        // draining without the lock is not available even on the holding
        // thread, because failing a waiter cascades into [unregister] and
        // [forgetInterests], which re-take this same lock. Ending that skip
        // takes a lock-level bypass, and is tracked as its own design task.
        // An acquire failure does not skip: this thread holds nothing, so the
        // sweep runs, unguarded like everything else on a loop whose
        // exclusion is already gone, and still ends its waiters.
        if (regLockStuck.value != 0) {
            // Closed even though the write is unguarded here. Exclusion is
            // already lost, so the write is no less safe than the reads around
            // it — and leaving the ledgers open is worse than either: every
            // later registration would append to a loop that will never arm it
            // and park forever, which is the hang this sweep exists to end.
            ledgersClosed = true
            logger.error {
                "${this::class.simpleName}: skipping the stop sweep — the registration lock is held " +
                    "after a failed release, so waiters stay parked and listeners are not told"
            }
            return
        }
        val stranded = mutableListOf<Registration>()
        val told = mutableListOf<LoopParticipant>()
        withRegLock {
            registrations.forEachValue { head -> drainChainInto(head, stranded) }
            registrations.clear()
            // The pipeline ledger is cleared without notifying from it. Both
            // ledgers empty here, or an entry left behind holds its listener --
            // and through it the transport, channel and pipeline graph -- for as
            // long as this stopped loop object is alive. Who gets *told* is the
            // registry's business: keyed on the ledger, the notification missed
            // the connection most likely to be waiting -- a paused one, whose
            // one-shot entry was consumed and whose re-arm declined -- and told
            // a two-interest connection twice.
            callbackRegistrations.clear()
            told.addAll(participants)
            participants.clear()
            // Closed in the same critical section that emptied them, so "swept"
            // and "closed" are one step. Anything arriving after this -- from
            // the notifications below, from the final drain, or from another
            // thread -- is refused rather than parked in a map nobody reads.
            ledgersClosed = true
        }
        // Outside the lock, as in cancelAll: a handler may re-enter this class.
        // The lists above are the cost of that, and this runs once per loop.
        //
        // Both loops are guarded, for two different reasons. The participant
        // one runs user code directly -- through onLoopStopped, a transport
        // teardown and the pipeline behind it -- and one that throws must not
        // strand the rest. The waiter one does not: a cancellation handler that
        // throws is taken by the coroutine machinery before this frame ever
        // sees it (where it goes from there is that coroutine's business, and
        // on this platform an unhandled one ends the process -- not something
        // a guard here could have caught either way). What does arrive is the
        // same thing every hand-off in this class can meet -- a dispatcher
        // refusing to take the cancellation back -- and it must not escape
        // this frame: under `loop()`'s `finally` that ends the process at the
        // pthread entry, and on the closing thread (a loop that never ran) it
        // would fail the `close()` mid-teardown.
        for (reg in stranded) {
            deliverOrRelease(reg, "cancelling the stranded waiter for, while the loop stops,") {
                val cause = CancellationException("EventLoop stopped before arming fd=${it.fd} for ${it.interest}")
                it.continuation.cancel(cause)
            }
        }
        for (participant in told) {
            try {
                participant.onLoopStopped()
            } catch (t: Throwable) {
                logger.warn(t) { "participant threw from onLoopStopped while the EventLoop was stopping" }
            }
        }
        // Deliver what either loop queued. Neither engine overrides
        // isDispatchNeeded, so a resume lands on this loop's queue even though
        // the sweep already runs on its thread -- and a listener told the loop
        // stopped can queue as readily as a cancelled waiter can: teardown
        // ends the flush wait of a handler parked on this very dispatcher.
        // Unconditional, deliberately: every predicate written
        // here so far under-delivered somewhere (gating on `stranded` alone
        // missed the write-only client this sweep exists for; gating on the
        // participants told skips a boss loop, which has none), and the drain
        // is idempotent, near-free when the queue is empty, and runs once per
        // loop lifetime.
        drainTasks()
    }

    /**
     * The loop thread's entry point: publish the thread, run [loopBody], and
     * take the loop apart in an order the rest of the class depends on.
     */
    fun loop() {
        if (!claimLoopTermination()) {
            // Reported, not thrown. This runs as a pthread entry point with
            // nothing above it to catch, so throwing would end the process --
            // while the first, healthy loop is still serving every connection
            // on this engine. Returning skips the terminal sequence below, and
            // that is right: whoever holds the claim runs it, and this entry
            // has no loop left to take apart. Reaching here with a `close()`
            // holding the claim means this `loop()` was invoked directly after
            // that close: a thread `start()` spawned cannot, since `close()`
            // takes the join path whenever one exists and a post-close
            // `start()` is refused before it creates anything. Either way it is
            // a caller bug.
            logger.error { "${this::class.simpleName}.loop() found the loop already claimed; this entry is ignored" }
            return
        }
        eventLoopThread = pthread_self()
        try {
            loopBody()
        } catch (loopFailure: Throwable) {
            // Recorded, then re-raised as before. Every way out of the body
            // is indistinguishable from here on -- they all reach the same
            // terminal sequence, which ends every wait it finds -- and only a
            // stop that was asked for is a cancellation. This covers the body
            // that throws; the ones that break out and return normally record
            // through [recordLoopFault] before they do.
            //
            // Before the terminal sequence rather than inside it: that is what
            // publishes the shutdown flags a reader synchronises on, so a
            // record made after them could be missed by the very readers it
            // exists for.
            handoff.recordLoopFailure(loopFailure)
            throw loopFailure
        } finally {
            terminate()
        }
    }

    /**
     * Records that this loop is ending for a reason nobody asked for.
     *
     * The `catch` in [loop] covers a body that throws. It is not the only way a
     * loop ends on its own, and on these engines it is the rarest: a body
     * running as a `pthread` entry point cannot usefully throw — there is
     * nothing above it to catch — so both engines answer a fatal poll errno,
     * and a registration lock that stopped being exclusive, by breaking out and
     * returning normally. That return is indistinguishable from the one a stop
     * request produces unless the reason is written down here first.
     *
     * Call it **before** leaving the body, so the record precedes the shutdown
     * flags [terminate] publishes and every reader that synchronises on one of
     * them sees it. Which is also why every caller is a body that is on its way
     * out rather than whatever detected the fault: a registration lock stops
     * being exclusive on whichever thread was taking it, at a moment that says
     * nothing about whether this loop is ending or was already asked to. The
     * body's own check answers that, and answers it in the right order.
     */
    protected fun recordLoopFault(cause: Throwable) {
        handoff.recordLoopFailure(cause)
    }

    /**
     * What ended this loop when nothing asked it to, or `null` if it ended
     * because it was asked to.
     *
     * For a transport deciding what to tell a caller whose flush this loop
     * will never run. Only meaningful once the loop is known to be gone —
     * [isStopped] and [isFinishing] are how that is known, and the record is
     * published before either of them.
     */
    fun loopFailure(): Throwable? = handoff.loopFailure()

    /**
     * Takes the claim that says who runs the terminal sequence.
     *
     * One claim for two takers — the loop thread on its way in, and a
     * [finishWithoutRunning] caller closing a loop that has no thread. Whoever
     * takes it owns the ledgers for the rest of the loop's life, which is what
     * makes the confinement the sequence relies on a fact rather than an
     * assumption: a `loop()` arriving after a close finds the claim taken and
     * returns without walking anything. That is not what makes a post-close
     * `start()` safe, though — by the time its `loop()` could refuse, the
     * thread and its handle already exist. The engines refuse `start()` on a
     * claimed loop for that.
     */
    private fun claimLoopTermination(): Boolean = loopEntered.compareAndSet(0, 1)

    /**
     * Whether this loop's termination is already spoken for — by a running or
     * finished [loop], or by [finishWithoutRunning].
     *
     * For a subclass deciding whether starting a thread can still mean
     * anything. A sequential answer: it says nothing about a caller racing a
     * concurrent close.
     */
    protected fun isTerminationClaimed(): Boolean = loopEntered.value != 0

    /**
     * Takes the loop apart, on the thread that claimed it.
     *
     * Split out of [loop] because a loop that never ran needs the same
     * sequence, in the same order, run by whoever closes it — see
     * [finishWithoutRunning]. Every step here assumes it is the only thread
     * touching this loop's state, which the claim is what guarantees.
     */
    private fun terminate() {
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
            // Nested so a throw from the drain cannot skip the sweep
            // while still publishing quiescence: that combination would
            // strand every waiter, with nothing left to end them.
            try {
                drainTasks()
            } finally {
                // After the last drain, before quiescence is published:
                // anything still in the ledger is waiting for an arm that
                // can no longer issue. Ended here, on the claiming thread,
                // while the ledger is still this loop's to walk. It drains
                // again itself: cancelling a waiter queues its resume back
                // onto taskQueue.
                failWaitersOnStoppedLoop()
            }
        } finally {
            handoff.markQuiescent()
            // Last, and on this thread while it still exists: after it
            // returns the id can be handed to a new thread, and a stale
            // non-null here would tell that thread it *is* the loop. It
            // would then act directly on state only the loop may touch —
            // walking the outbound chain, mutating pending writes, issuing
            // syscalls on this fd — off any loop at all, against a teardown
            // running elsewhere. Every caller that asks reads `null` from
            // here on and takes its off-loop path, which is the truth.
            //
            // After [markQuiescent], not before: the final drain and the
            // stop sweep run on this thread and assert they are on the
            // loop. Nothing runs here afterwards but the thread's own
            // return, so there is no window in which the id is needed and
            // already gone.
            eventLoopThread = null
        }
    }

    /**
     * Runs the terminal sequence for a loop that never had a thread, on the
     * caller's.
     *
     * A loop can be closed without ever having run. Nothing published
     * `finished` or `quiescent` for such a loop, so [LoopHandoff.runOnLoop]
     * reads "live", hands work over — and no drain ever comes. Anything
     * dispatched at it is lost: a transport's teardown, and with it the
     * descriptor it would have released; an accepted connection the accept
     * path handed on.
     *
     * **Who closes such a loop**: the subclass sends `close()` here when it wins
     * the CAS that takes the loop down and reads its thread flag as never set —
     * not on the termination claim, which this function takes itself at the top
     * and may not get. So the callers are whoever reaches `close()` on an
     * unstarted loop rather than a set of special cases. Tests that build one and never start it, and, in
     * production, each unwind of a partly-built engine: an EventLoop group
     * rolling back its own constructor, a group rolling back a `start()` that
     * failed part way, and an engine closing a group or a boss loop it had
     * built before something else in its construction threw. A present set,
     * not a future one.
     *
     * **What this contributes on those routes** is the terminal sequence, not
     * the release: the work dispatched at the loop runs here — a transport's
     * teardown, and with it the descriptor *that* would have released — and the
     * loop is left quiescent, which is the condition `close()` refuses to
     * release under when it is not met. The loop's own descriptors, native
     * scratch and allocator child are returned by the subclass's
     * `releaseLoopResources()`, on this path and the joined one alike.
     *
     * The caller may walk the ledgers here because it holds the claim, and
     * because it publishes itself as the loop thread for the duration: every
     * assertion and every confinement argument in this class stays literally
     * true.
     *
     * **A `start()` racing this call is not made safe by the claim.** It reads
     * the claim before this takes it, so it goes on to create a thread and
     * write the handle into the arena this call is about to release; what
     * loses the claim is the spawned `loop()`, after both have happened. The
     * engines refuse `start()` on a claimed loop for the sequential case and
     * say there that it is ordering, not synchronisation. Nothing in the tree
     * starts a loop concurrently with closing it.
     *
     * **Call it from `close()`, after the flag that says the loop is closing.**
     * The sequence runs application teardown, and a participant that closes
     * the engine from its own stop notification re-enters `close()` — which
     * no-ops there only because that flag is already down. Called directly,
     * that re-entry reaches the resource release while this sequence is still
     * walking the registry.
     *
     * @return `true` when this call ran the sequence. `false` means it was
     *   already claimed — by a running or finished [loop], or by another
     *   [finishWithoutRunning]. The caller owns deciding what that means: a
     *   thread it created is one it must join, while a [loop] invoked directly
     *   and a second closer both leave nothing to join at all.
     */
    fun finishWithoutRunning(): Boolean {
        if (!claimLoopTermination()) return false
        eventLoopThread = pthread_self()
        terminate()
        return true
    }

    /**
     * Registers [listener] for [fd] + [interest] and arms it.
     *
     * **The registration is one-shot**: readiness dispatch removes it before
     * invoking, so a listener that wants the next event has to register again —
     * which is what a READ transport's `armRead()` does on every wake, and what
     * declining to do gives up along with peer-close detection (see
     * [FdReadyListener.onPeerClosed]).
     *
     * The entry goes in before the arming syscall, for the same reason
     * [register] does it in that order: the loop can report readiness the
     * instant the kernel accepts the registration, and a listener that is not
     * in the map yet would be a dropped event.
     *
     * **Thread safety**: safe from any thread. Off the loop the arm is queued.
     *
     * **Returns the arm's failure, or `null`.** A failed on-loop arm is
     * withdrawn and its failure handed back, so the caller's own frame can
     * act on a listener that will never fire; a dispatched off-loop arm has
     * no frame to answer and returns `null` — see [armRegisteredCallback]
     * for what that leaves.
     *
     * **A registration arriving after the loop has stopped is refused**, and
     * the refusal answers `null` too — nothing is appended and no arm is
     * issued, but deliberately no failure either: the sweep owns that answer
     * channel (see the branch's comment below). What the caller gets instead is a
     * WARN naming the fd and interest, because a listener refused here is one
     * that will never fire. Whether anyone was *told* is the registry's
     * business, not this ledger's: a transport that joined as a
     * [LoopParticipant] was told once at the sweep, whatever it held here — but
     * a bare listener that never joined gets only this WARN, which is why the
     * refusal is not silent.
     *
     * **A second registration on the same `(fd, interest)` replaces the first**,
     * which is then never called and never told. That is what a re-arm is —
     * `armRead()` registers again on every wake — so the ledger holds one
     * listener per key by design, not one per registrant.
     */
    fun registerCallback(fd: Int, interest: Interest, listener: FdReadyListener): Throwable? {
        val key = registrationKey(fd, interest)
        val appended = withRegLock {
            if (ledgersClosed) {
                false
            } else {
                putCallback(key, listener)
                true
            }
        }
        if (!appended) {
            // Refused, and reported rather than dropped: a listener that will
            // never fire is not something to drop in silence. What is *not*
            // done is calling a hook from the refusal --
            // a listener registering from inside its own `onLoopStopped` would
            // then be refused, told, and re-register without bound, which is the
            // recursion an arm-failure hook produced in an earlier change.
            //
            // Whether anyone was told is the registry's business: a
            // [LoopParticipant] was told once at the sweep, whatever it held in
            // this ledger. A bare listener that never joined gets only this
            // WARN, which is why the refusal is not silent.
            logger.warn {
                "${this::class.simpleName}.registerCallback: EventLoop stopped — refusing " +
                    "fd=$fd ${interest.name}; this listener will not fire"
            }
            // Null rather than the refusal: the sweep has told (or is telling)
            // this participant the loop stopped, which is the answer channel a
            // stopped loop owns -- returning a second one here is what invited
            // the re-register recursion this branch's comment recounts.
            return null
        }
        return armRegisteredCallback(fd, interest, key, listener)
    }

    /**
     * Joins [participant] to the registry and registers [listener] for
     * [fd] + [interest] **as one step**, reporting whether it took.
     *
     * For the caller that owns a descriptor and needs both halves or neither.
     * Calling [addParticipant] and then [registerCallback] does not give that:
     * each takes the registration lock on its own, so the stop sweep — which sets
     * `ledgersClosed` under that same lock — fits between them. The transport
     * that did so could end up a participant with no callback, and then be told
     * `onLoopStopped` after its owner had given up on it and closed the fd, by
     * which time the number may be somebody else's. Reading `ledgersClosed` once,
     * inside one acquisition, is what makes "both or neither" true rather than
     * merely likely — as far as the lock holds. [withRegLock] runs its block even
     * when the acquire failed, and on that path this reads and mutates the
     * ledgers unguarded and can still answer `true`; the failure is reported and
     * treated as fatal to the loop elsewhere, so the atomicity here is exactly as
     * good as that contract and no better.
     *
     * The arm happens after the lock is released, exactly as in
     * [registerCallback] — holding the registration lock across a syscall is what
     * the rest of this class is careful not to do.
     *
     * A refusal means the loop has swept: **the descriptor is the caller's to
     * release**, and the caller can do that through the transport's own teardown
     * rather than closing the fd behind its back. See [addParticipant]'s KDoc for
     * why the refusal is not a throw.
     *
     * @return `true` if both registrations took, `false` if the loop refused —
     *   in which case neither was made.
     */
    fun joinLoop(
        participant: LoopParticipant,
        fd: Int,
        interest: Interest,
        listener: FdReadyListener,
    ): Boolean {
        val key = registrationKey(fd, interest)
        val joined = withRegLock {
            if (ledgersClosed) {
                false
            } else {
                participants.add(participant)
                putCallback(key, listener)
                true
            }
        }
        if (!joined) {
            logger.warn {
                "${this::class.simpleName}.joinLoop: EventLoop stopped — refusing fd=$fd " +
                    "${interest.name}; ${participant::class.simpleName} will not be told and " +
                    "this listener will not fire"
            }
            return false
        }
        armRegisteredCallback(fd, interest, key, listener)
        return true
    }

    /**
     * Arms the kernel interest for a callback that is already in the ledger.
     *
     * The funnel spelled out rather than [submitOnLoop], because the two branches
     * genuinely need different things and this one is the per-readiness-event
     * re-arm. Shared by [registerCallback] and [joinLoop], which differ only in
     * what they put in the ledger first.
     *
     * Queued from off the loop, the arm outlives the call: a teardown can
     * withdraw `listener` and close the fd before it runs, and by then the number
     * may be somebody else's. The check is by identity, not presence -- the
     * ledger holds one entry per key, so a replacement would pass a presence test
     * and then be what an arm failure withdraws.
     *
     * On the loop thread there is nothing to check. Withdrawal is loop-confined
     * -- both in-tree teardowns run on the loop, and that is what makes this path
     * sound rather than the check, which does not close its own window either way
     * -- so nothing can withdraw between the append and the arm. A check there
     * would be a lock acquisition every connection pays on every wake to prove
     * something already true.
     */
    private fun armRegisteredCallback(fd: Int, interest: Interest, key: Long, listener: FdReadyListener): Throwable? {
        if (inEventLoop()) {
            return submitArmCallback(fd, interest, key, listener)
        }
        val armIfStillRegistered = Runnable {
            if (withRegLock { isCallbackRegistered(key, listener) }) {
                // A failure here has no caller frame to return into -- the
                // withdrawal's ERROR log is the report, as it was for every
                // arm before the chain learned to answer. The off-loop arms
                // in this tree are the initial read arms made through
                // joinLoop off the worker loop -- the connect paths and the
                // coroutine-mode accept build their channels on the calling
                // thread; joinLoop also discards the on-loop answer, and its
                // answer channel is its own follow-up. (The server's accept
                // arms no longer come here off-loop: start() hands them to
                // the boss loop, so a refusal ends the listener in its own
                // frame.)
                submitArmCallback(fd, interest, key, listener)
            }
        }
        dispatch(EmptyCoroutineContext, armIfStillRegistered)
        return null
    }

    /**
     * Withdraws the callback registered for [fd] + [interest], if any.
     *
     * Here rather than in each engine, alongside [registerCallback]: the callers
     * of both are the transport on teardown and the pipelined server when it
     * stops accepting, and the consolidation brought those into this module.
     * They no longer reach across a boundary, but splitting the pair would not
     * narrow anything either — the entry point into the ledger is open here, and
     * withdrawal is the safer half to keep beside it.
     *
     * **The kernel interest is left armed.** Whoever owns the fd disarms it, and
     * until they do the registration keeps re-firing into readiness dispatch's
     * no-handler branch — a WARN per wake on kqueue's persistent filter, and per
     * wake on epoll because it is level-triggered. Spelled out here rather than
     * deferred to the private member that does the removal, because this is the
     * published half and a caller cannot follow the pointer.
     *
     * **Thread safety**: safe from any thread — it takes the lock and nothing
     * else.
     */
    fun unregisterCallback(fd: Int, interest: Interest) {
        withRegLock { popCallback(registrationKey(fd, interest)) }
    }

    /**
     * Adds [participant] to this loop's registry, so [failWaitersOnStoppedLoop]
     * tells it once when the loop stops.
     *
     * Refused with a WARN once the loop has swept — the same shape as
     * [registerCallback]'s refusal, and for the same reason: nothing arrives
     * after the sweep that the sweep will ever read. Refusal rather than a
     * throw, deliberately: a throw would unwind through the caller that had
     * just accepted or connected the descriptor, leaking it. The construction
     * sites act on the refusal instead, releasing the fd themselves — which is
     * why [joinLoop], the form the transports use, reports it rather than
     * hiding it.
     *
     * The POSIX transports do not call this directly; they call [joinLoop],
     * which takes the participant slot and the read callback in one acquisition
     * of the lock. Direct callers are the tests that need a bare participant.
     *
     * **Thread safety**: safe from any thread; takes the registration lock.
     */
    fun addParticipant(participant: LoopParticipant) {
        val added = withRegLock {
            if (ledgersClosed) {
                false
            } else {
                participants.add(participant)
                true
            }
        }
        if (!added) {
            logger.warn {
                "${this::class.simpleName}.addParticipant: EventLoop stopped — refusing; " +
                    "${participant::class.simpleName} will not be told"
            }
        }
    }

    /**
     * Removes [participant] from the registry, ending the loop's obligation to
     * tell it. Called from the participant's own teardown; after the sweep the
     * registry is already empty and this is a no-op.
     *
     * **Thread safety**: safe from any thread; takes the registration lock.
     */
    fun removeParticipant(participant: LoopParticipant) {
        withRegLock { participants.remove(participant) }
    }

    /**
     * Dispatches a ready event for [fd] + [interest] to the appropriate handler.
     *
     * Checks callback registrations first (pipeline path), then suspend
     * registrations (Channel path).
     *
     * **Pipeline path**: [FdReadyListener.onReady] first, then
     * [FdReadyListener.onPeerClosed] when [eofFlag] is set; only after both does
     * it check whether the callback was re-registered, because either one may
     * re-arm. A READ callback normally does, synchronously via `armRead()` — but
     * not when the listener declines the data that woke it (`readEnabled = false`,
     * the back-pressure case), which is a READ callback reaching the disarm arm of
     * this check on purpose. WRITE callbacks that
     * complete a successful flush do NOT re-arm; in that case
     * [removeInterest] takes the write interest back. Without that, both engines
     * keep reporting it on every wait — kqueue because `EV_ADD` is persistent,
     * epoll because it is level-triggered — a busy loop that saturates the loop
     * thread once many connections have completed their writes.
     *
     * **Suspend path**: after popping one waiter, the interest is taken back when
     * the chain empties, because the resumed coroutine may not re-register
     * immediately — unlike the pipeline path's synchronous `armRead` cycle.
     *
     * Either path takes the interest back only when *both* ledgers are empty for
     * the key: a callback and a suspend waiter can sit on one `(fd, interest)`,
     * and disarming for one strands the other.
     *
     * **Stale-interest safety net**: when neither a callback nor a suspend waiter
     * is found, a WARN is logged and the interest is taken back. Without that, the
     * registration re-fires until the fd is closed.
     *
     * **Neither hand-off may end the loop.** Both parties run code this loop does
     * not own — a listener's `onReady`, a waiter's dispatcher — and this frame sits
     * under the loop body and the `pthread` entry, which catches nothing. Each is
     * guarded where it is called, reported at ERROR, and the loop goes on serving
     * the rest; what differs is only what is taken back afterwards, which the two
     * sites explain in place.
     */
    protected fun dispatchReady(fd: Int, interest: Interest, eofFlag: Boolean) {
        assertInEventLoop("dispatchReady")
        val key = registrationKey(fd, interest)
        val cb = withRegLock { popCallback(key) }
        if (cb != null) {
            // Order: drain (onReady) before close (onPeerClosed) for combined
            // data-and-EOF events. For pure EOF (no pending data) the listener
            // can detect "no more data" via the read syscall in onReady — the
            // standard `read()` returns 0 path — so unconditionally calling
            // onReady first keeps the contract simple. When the listener
            // declines the wake -- a transport with reads disabled returns
            // from onReady without reading -- this call is the only one left
            // that can surface the close. How far that covers a given
            // connection is the transport's to state, and it does, at the arm
            // its channel attach makes.
            // Backstop, below the two guards that know what a failure means:
            // a server's accept loop closes the descriptor it could not prepare,
            // a transport closes the connection whose readiness it could not
            // handle. Anything still arriving here is a listener this class does
            // not recognise, or one of those guards itself failing -- in both
            // cases the unit that died is unknown, so this cannot release
            // anything on its behalf and does not pretend to.
            //
            // What it does buy is the loop. Without it the throw leaves the
            // readiness dispatch, the loop body and the pthread entry that has
            // nothing above it to catch, and the process ends -- taking every
            // other connection on this engine with the one that failed.
            //
            // Popping the entry above is not by itself enough to stop the fd
            // re-firing into the same throw. A listener that re-arms before it
            // does its work has put a fresh entry back before it throws, and on
            // a level-triggered interest the next iteration finds it and calls
            // straight back in -- a hot loop logging one ERROR a turn. So does
            // one whose `onReady` earned its re-arm and whose `onPeerClosed`
            // then threw: EOF is permanently readable, so that pair repeats
            // every turn too. Either way the re-arm cannot be taken as evidence
            // the listener can proceed, so only an independent waiter keeps the
            // interest armed below.
            var listenerThrew = false
            try {
                cb.onReady(interest)
                if (eofFlag) cb.onPeerClosed(interest)
            } catch (listenerFailure: Throwable) {
                listenerThrew = true
                logger.error(listenerFailure) {
                    // Not "the interest is dropped": that is decided below, and
                    // a suspend waiter on the same key keeps it armed.
                    "${cb::class.simpleName} threw from readiness for fd=$fd $interest; " +
                        "its registration is dropped and whatever it held is not released"
                }
            }
            // The eof path used to disarm unconditionally, on the reasoning that
            // a connection reporting EOF is ending. Not true of every listener
            // that reaches here: a server's AcceptArm re-arms on both WouldBlock
            // and a failed accept, so disarming discarded a live registration and
            // left an accept loop that never ran again. On epoll it never came
            // back -- the disarm drops the fd from the interest list once nothing
            // is left, so even the always-reported EPOLLERR could not revive it.
            //
            // Both ledgers decide this, not just the callback one: a suspend
            // waiter queued on the same key still needs the interest armed.
            val keepInterest = withRegLock {
                // A listener that threw does not get to vouch for itself, for
                // the reason above. Its own re-arm is dropped here, so the
                // ledger and the kernel go on agreeing about what is
                // watched -- a ledger saying "armed" over an interest nobody
                // holds is how the stale-entry hangs this loop has already been
                // fixed for began. A waiter is a different party and is still
                // owed the interest, and keeping it armed for one is why the
                // drop above is of the registration and not of the interest.
                //
                // By identity, because by key alone would take a stranger's.
                // A listener that ends its connection closes the fd on the way
                // through here, and the number is free from that moment: a
                // connect on another thread can be handed it back and register
                // on this very key before this line runs. Dropping that would
                // leave a freshly opened channel that reports itself open,
                // never reads a byte and never learns of a close -- and, with
                // the interest taken back below, nothing to revive it. The
                // teardown's own withdrawal is key-only and safe because it
                // runs before the descriptor is closed; this one runs after.
                if (listenerThrew) popCallbackIfCurrent(key, cb)
                hasWaiters(key) || hasCallbackListener(key)
            }
            if (!keepInterest) {
                removeInterest(fd, interest)
            }
        } else {
            // Suspend path: pop one waiter from the FIFO chain. If siblings remain
            // (concurrent `accept()` callers on the same serverFd), leave the
            // interest armed so the next wait cascade-fires the next sibling; the
            // chain drains across iterations while the ready condition holds. Only
            // when it empties is the interest taken back, so the fd stops re-firing
            // while the resumed continuation finishes its I/O elsewhere.
            // One critical section, and no Pair to carry its two results out:
            // this runs per readiness event, and both engines took the lock once
            // here before the ledgers were shared.
            var keepInterest = false
            val popped = withRegLock {
                val head = popHeadRegistration(key)
                keepInterest = hasWaiters(key) || hasCallbackListener(key)
                head
            }
            if (popped != null) {
                if (!keepInterest) {
                    removeInterest(fd, interest)
                }
                // Guarded like the listener path above, and for the same
                // reason: this is the loop's own frame, the loop body has
                // nothing above it but the pthread entry, and an escape there
                // ends the process -- taking every other connection on this
                // engine with the one waiter that could not be resumed. The
                // reachable source is not the waiting code but the hand-off to
                // it: `resume` goes through the waiter's own dispatcher, which
                // is the caller's to choose and may refuse the work (a pool
                // shut down under it). Not the waiting code itself, even when
                // an Unconfined waiter runs inline in this frame -- a throw
                // from the resumed body is caught by the coroutine machinery
                // and routed to its own handler, so it never reaches here.
                //
                // Nothing is taken back on the way out. The registration left
                // the ledger at the pop above, so the fd cannot re-fire into
                // the same failure; the interest was already decided, by
                // whoever still claims it -- a waiting sibling, or a callback
                // that entered the ledger in the window since the pop.
                //
                // What the loop cannot do is complete the wait: whether the
                // resume took effect before the throw belongs to the
                // continuation's own state machine. What it can do is hand the
                // waiter back whatever it owned for the duration, which is the
                // hook `deliverOrRelease` runs -- without it a connect socket
                // outlives every path that could close it.
                // Non-capturing on purpose: this is the per-event site, and a
                // lambda that captured `popped` would be one allocation per
                // ready fd. Taking the registration as the parameter keeps it
                // a singleton.
                deliverOrRelease(popped, "resuming the readiness waiter for, and the loop goes on serving,") {
                    it.continuation.resume(Unit)
                }
            } else {
                // No handler at all: armed without one, or not taken back when the
                // last handler deregistered. Either way the registration re-fires
                // for as long as the fd is ready — a busy loop.
                //
                // The WARN and the disarm take different predicates, which is why
                // they are not one `if`. The invariant "a registered interest has
                // a handler behind it" was violated at the pop, whatever arrives
                // afterwards, so the diagnostic is unconditional -- as it was
                // before this class existed, and as the bind-registration tests
                // read it. The disarm is not: `keepInterest` covers the benign
                // case where a callback registered off the loop between the pop
                // and the check wants the interest kept, its own arm queued
                // behind us. Folding them together made an arrival silence the
                // record of the violation.
                logger.warn {
                    "${this::class.simpleName}.dispatchReady: no handler for fd=$fd ${interest.name} — " +
                        "removing its stale kernel registration"
                }
                if (!keepInterest) {
                    removeInterest(fd, interest)
                }
            }
        }
    }

    /**
     * Whether [listener] is still the entry on `key`. Caller holds the lock.
     *
     * The callback twin of [isRegistered], and asked at the same place: an arm
     * asks it before issuing a syscall, so a listener withdrawn in the meantime
     * is not armed and a replacement is not mistaken for it.
     */
    protected fun isCallbackRegistered(key: Long, listener: FdReadyListener): Boolean =
        callbackRegistrations[key] === listener

    /**
     * Withdraws [listener] from `key` if it is still the entry there, and
     * reports whether it was. Caller holds the lock.
     *
     * The callback twin of [removeRegistration], and the only withdrawal an arm
     * failure may use. Withdrawing by key alone would evict whatever is on the
     * key, which after a replacement is a listener whose own arm is still in
     * flight: that arm then finds itself gone and returns, leaving a registrant
     * with nothing armed and no error naming it. `false` therefore means the
     * failure belongs to a listener already superseded — the replacement owns
     * the key now, and reporting it would name the wrong one.
     */
    protected fun popCallbackIfCurrent(key: Long, listener: FdReadyListener): Boolean {
        if (callbackRegistrations[key] !== listener) return false
        callbackRegistrations.remove(key)
        return true
    }

    /**
     * Withdraws [listener] after its arm failed with [err], and says so at ERROR.
     *
     * Here rather than in each override because it is the half of
     * [submitArmCallback] that does not differ, and the half that already
     * drifted once: epoll's copy discarded the errno while kqueue's withdrew and
     * logged. [syscall] is the one word that does differ, passed in so the
     * message still names what failed.
     *
     * **Returns the failure it withdrew for**, so the arm chain can hand it to
     * the caller whose bytes just lost their future — the ERROR log used to be
     * the only signal, and a parked flush waiter hung until `close()` while
     * the queue sat unsendable (measured). The suspend arm's sibling has
     * answered its waiter this way all along ([submitArm] engines call their
     * `failUnarmedWaiter`); returning is the callback path's parity, shaped as
     * a value rather than a hook because a hook fired from a refusal is how
     * this path recursed before (a told listener re-registering without
     * bound).
     *
     * Null — and silent — when the withdrawal finds someone else on the key,
     * see [popCallbackIfCurrent]: the listener that failed is already
     * superseded, so there is nothing to withdraw, nothing true to say about
     * the key, and nothing for the superseded caller to act on.
     */
    protected fun withdrawFailedCallbackArm(
        fd: Int,
        interest: Interest,
        key: Long,
        listener: FdReadyListener,
        syscall: String,
        err: Int,
    ): Throwable? {
        if (!withRegLock { popCallbackIfCurrent(key, listener) }) return null
        // One copy: the log line and the returned failure must keep saying
        // the same thing, and the exception's copy is built unconditionally
        // anyway, so there is nothing for the log gate to save.
        val reason = "$syscall(fd=$fd, ${interest.name}) for callback failed: ${errnoMessage(err)}"
        logger.error { "$reason — readiness callback will not fire" }
        return IllegalStateException(reason)
    }

    /**
     * Whether `fd` still has a callback registered for [interest], taking the lock.
     *
     * The keyed pair [hasCallbackRegistration] wraps. This one is `protected`,
     * which reaches subclasses; the wrapper is behind the opt-in marker for the
     * callers that are not — the group, the engine, and the seam tests through
     * them. Neither modifier hides anything from the API docs: this project
     * documents every visibility, down to `private`. The names differ because a same-named wrapper
     * would have to `override` rather than wrap, which is what the fixture's
     * double does.
     */
    protected fun hasCallbackFor(fd: Int, interest: Interest): Boolean =
        withRegLock { hasCallbackListener(registrationKey(fd, interest)) }

    /**
     * How many participants this loop currently holds, taking the lock.
     *
     * A probe, like [hasCallbackFor]: what it exists to make observable is the
     * remove half of the registry contract. A transport that closes must leave,
     * or a long-lived loop with connection churn grows this set without bound —
     * the retention this registry was built to end, reintroduced by a missing
     * `removeParticipant`.
     */
    @InternalReadinessEngineApi
    fun participantCount(): Int = withRegLock { participants.size }

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
    private fun hasCallbackListener(key: Long): Boolean = callbackRegistrations[key] != null

    /**
     * Registers [listener] on `key`, replacing any predecessor. Caller holds the lock.
     *
     * `private`, as is every other accessor on this map except
     * [isCallbackRegistered] and [popCallbackIfCurrent] — the pair an engine's
     * arm needs, both scoped to one listener. Nothing outside can name a key and
     * mutate what is on it. The lock discipline is still what holds.
     */
    private fun putCallback(key: Long, listener: FdReadyListener) {
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
    private fun popCallback(key: Long): FdReadyListener? = callbackRegistrations.remove(key)

    private companion object {
        /** Bit position of the interest half in a [registrationKey]; the fd occupies the low 32. */
        private const val KEY_INTEREST_SHIFT = 32

        /** The fd half of a [registrationKey]: the low 32 bits, without sign extension. */
        private const val FD_MASK = 0xFFFFFFFFL
    }
}
