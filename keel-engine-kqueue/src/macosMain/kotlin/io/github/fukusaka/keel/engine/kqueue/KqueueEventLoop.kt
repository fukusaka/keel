package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.MpscQueue
import io.github.fukusaka.keel.collections.LongObjectMap
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
import io.github.fukusaka.keel.native.posix.LoopHandoff
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import io.github.fukusaka.keel.pipeline.DeadlineScheduler
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.darwin.EVFILT_READ
import platform.darwin.EVFILT_WRITE
import platform.darwin.EV_EOF
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.pthread_create
import platform.posix.pthread_equal
import platform.posix.pthread_join
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.pthread_self
import platform.posix.pthread_t
import platform.posix.pthread_tVar
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.TimeSource

/**
 * Single-threaded kqueue event loop for macOS, also serving as a [CoroutineDispatcher].
 *
 * Drives all I/O for channels created by [KqueueEngine]. A dedicated
 * pthread runs [loop], interleaving three tasks:
 * 1. Execute queued coroutine continuations ([taskQueue])
 * 2. Call `kevent()` to wait for fd readiness events
 * 3. Resume suspended coroutines when their fds become ready
 *
 * **CoroutineDispatcher integration**: By extending [CoroutineDispatcher],
 * coroutines dispatched on this EventLoop (e.g., `launch(eventLoop) {}`)
 * execute entirely on the EventLoop thread. When `cont.resume()` is called,
 * the continuation is dispatched back to this same thread via [dispatch],
 * eliminating cross-thread dispatch overhead. This matches Netty's model
 * where channelRead/write run on the EventLoop thread.
 *
 * **Thread model**: The EventLoop thread is created via `pthread_create`
 * rather than Kotlin/Native's `Worker` (deprecated) or coroutine dispatchers
 * (unnecessary overhead for a tight syscall loop).
 *
 * **Wakeup mechanism**: A `pipe(2)` fd pair is registered with kqueue.
 * External threads call [wakeup] to write 1 byte to the pipe, causing
 * `kevent()` to return immediately so the EventLoop can process newly
 * registered fds or queued tasks.
 *
 * **Scalability**: Each EventLoop instance is single-threaded.
 * [KqueueEventLoopGroup] creates multiple instances and distributes
 * channels in round-robin for multi-threaded I/O.
 *
 * **Thread safety**: [registrations] is protected by `pthread_mutex_t`.
 * [taskQueue] uses a lock-free MPSC queue ([MpscQueue]) — CAS-based
 * enqueue (~5-10ns) replaces mutex lock/unlock (~50-100ns) on the
 * dispatch hot path.
 *
 * ```
 * EventLoop thread (single loop iteration):
 *   1. drainTasks()    — run coroutine continuations
 *   2. kevent(timeout) — block until events or wakeup
 *      timeout = 0 if tasks pending, null otherwise
 *   3. for each ready fd:
 *        if wakeup pipe: consume byte, continue
 *        dispatchReady(fd, interest):
 *          pipeline path: callback.onReady(); if not re-registered → EV_DELETE
 *          suspend path:  pop FIFO head; if chain empty → EV_DELETE;
 *                         if no handler at all → WARN + EV_DELETE
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueEventLoop(
    internal val logger: Logger,
    /**
     * Per-EventLoop [BufferAllocator] instance. Co-located with the loop
     * (rather than tracked separately in [KqueueEventLoopGroup]) so callers
     * receive the allocator-loop pair as a single object — eliminating the
     * `Pair<EventLoop, BufferAllocator>` allocation that the previous
     * `EventLoopGroup.next()` API created on every accept. Default is
     * [DefaultAllocator] for boss / test loops that do not perform reads
     * and therefore never invoke the allocator.
     */
    val allocator: BufferAllocator = DefaultAllocator,
    /**
     * Engine-wide default read buffer size
     * ([io.github.fukusaka.keel.core.IoEngineConfig.readBufferSize]) for
     * connections on this loop. Used as the fallback when a connection's
     * [io.github.fukusaka.keel.core.BindConfig.readBufferSize] /
     * [io.github.fukusaka.keel.core.ConnectConfig.readBufferSize] is `null`;
     * the effective size is captured per connection on the transport.
     */
    val readBufferSize: Int = IoTransport.DEFAULT_READ_BUFFER_SIZE,
    /**
     * Engine-wide default idle (no-progress) timeout in milliseconds
     * ([io.github.fukusaka.keel.core.IoEngineConfig.idleTimeoutMillis]) for
     * connections on this loop (`0` = disabled). Fallback when a connection's
     * [io.github.fukusaka.keel.core.BindConfig.idleTimeoutMillis] /
     * [io.github.fukusaka.keel.core.ConnectConfig.idleTimeoutMillis] is `null`.
     */
    val idleTimeoutMillis: Long = 0,
    /**
     * Engine-wide [io.github.fukusaka.keel.core.IoEngineConfig.flushCoalescing]
     * value. When `true` (default), [KqueueIoTransport.flush] schedules the
     * actual send onto the next EL tick via [dispatch] so that same-tick
     * per-emit `requestFlush` calls collapse into one `writev(2)`. When
     * `false`, each `flush()` sends immediately (pre-#899 behaviour).
     */
    val flushCoalescing: Boolean = true,
    private val syscallOps: KqueueSyscallOps = PosixKqueueSyscallOps,
) : CoroutineDispatcher(), KqueueSuspendRegister {

    /**
     * The kqueue file descriptor, created at construction.
     * Exposed for [KqueueEngine.bind] to register server fds directly
     * via `kevent(kqFd, ...)`. Channel fds are registered via [register].
     */
    val kqFd: Int

    // Arena for long-lived native allocations (mutexes).
    // Freed in close().
    private val arena = Arena()

    // Separate mutexes for registrations and taskQueue to minimize lock
    // contention: dispatch() (any thread) and register() (coroutine thread)
    // are independent hot paths that should not block each other.
    private val regMutex = arena.alloc<pthread_mutex_t>().apply {
        val initRet = pthread_mutex_init(ptr, null)
        check(initRet == 0) { "pthread_mutex_init() failed: ${errnoMessage(initRet)}" }
    }
    private val registrations = LongObjectMap<Registration>()

    // Callback registrations for pipeline (non-suspend) I/O.
    // Separated from coroutine registrations to avoid sealed-class overhead.
    // Listener interface (instead of `() -> Unit`) lets each `IoTransport`
    // pass `this` to [registerCallback], avoiding per-call lambda allocation
    // on the read re-arm fast path. Mirrors the `Job : DisposableHandle`
    // precedent from kotlinx.coroutines.
    private val callbackRegistrations = LongObjectMap<FdReadyListener>()

    /**
     * Number of live callback registrations, for tests that need to see a
     * teardown actually withdraw one. The map is keyed by fd number, so a
     * registration left behind is not visible from the outside in any other
     * way: it is not a growing leak (the next connection on that fd number
     * overwrites it) but it does keep the transport, its channel and the whole
     * pipeline graph reachable until then.
     */
    internal val callbackRegistrationCount: Int
        get() = withRegLock { callbackRegistrations.size }

    // Lock-free MPSC queue replaces pthread_mutex + MutableList for
    // dispatch hot path. CAS (~5-10ns) vs mutex lock/unlock (~50-100ns).
    private val taskQueue = MpscQueue<Runnable>()

    // Reusable scratch buffer for [drainTasks]. Kept as a field so the
    // EventLoop hot path does not allocate a new list each iteration.
    // Only accessed from the EventLoop thread (via [drainTasks]).
    private val drainBatch: MutableList<Runnable> = mutableListOf()

    // Pre-allocated event carrier array reused across every [loop]
    // iteration. Sized to [MAX_EVENTS]; each slot is a mutable [KqEvent]
    // whose fields are overwritten by [KqueueSyscallOps.waitEvents].
    // Only accessed from the EventLoop thread.
    private val eventBuffer: Array<KqEvent> = Array(MAX_EVENTS) { KqEvent() }

    /**
     * Shared gather-write scratch: one native (bases, lens) pair reused by
     * every transport flush on this loop (see [KqueueIoTransport.flushGather]). Kept per
     * EventLoop rather than per connection so every flush on this thread
     * touches the same hot memory — mirroring the locality of the memScoped
     * arena this replaced — while performing no per-flush allocation.
     * EL-confined like [eventBuffer]; freed once in [close] (safe: the loop
     * thread is joined there, so no flush can be in flight).
     */
    internal var writevBases: CPointer<CPointerVar<ByteVar>> = nativeHeap.allocArray(INITIAL_WRITEV_CAPACITY)
        private set

    /** Byte lengths (`size_t`) paired with [writevBases]. */
    internal var writevLens: CPointer<ULongVar> = nativeHeap.allocArray(INITIAL_WRITEV_CAPACITY)
        private set

    private var writevCapacity: Int = INITIAL_WRITEV_CAPACITY

    /**
     * Grows [writevBases] / [writevLens] (1.5x, at least [n]) so a gather
     * flush of [n] regions fits. EventLoop thread only — callers run inside
     * the flush path, which is confined to this loop.
     */
    internal fun ensureWritevCapacity(n: Int) {
        if (writevCapacity >= n) return
        val grown = maxOf(writevCapacity + (writevCapacity shr 1), n)
        nativeHeap.free(writevBases)
        nativeHeap.free(writevLens)
        writevBases = nativeHeap.allocArray(grown)
        writevLens = nativeHeap.allocArray(grown)
        writevCapacity = grown
    }

    private val wakeupFds = IntArray(2) // [readFd, writeFd]

    // Cached byte arrays to avoid per-wakeup allocation.
    // wakeup() is called once per dispatch/register, so reuse matters.
    private val wakeupWriteBuf = byteArrayOf(1)
    private val wakeupReadBuf = ByteArray(WAKEUP_DRAIN_SIZE)
    private val running = AtomicInt(1) // 1 = running, 0 = stopped

    // Off-loop -> loop hand-off, plus the two shutdown-progress flags it
    // gates on. Shared with the sibling POSIX readiness engine: the window it
    // closes is narrow enough that a fix applied to one copy and not the other
    // would leave that one wedging or spinning.
    private val handoff = LoopHandoff(
        inEventLoop = ::inEventLoop,
        dispatchToLoop = { task -> dispatch(EmptyCoroutineContext, Runnable { task() }) },
    )
    private val threadPtr = arena.alloc<pthread_tVar>()

    @kotlin.concurrent.Volatile
    private var eventLoopThread: pthread_t? = null

    /**
     * A pending I/O interest for a file descriptor.
     *
     * Multiple [Registration]s with the same `(fd, interest)` key form a
     * singly-linked FIFO chain via [next]. The chain head doubles as the
     * map entry; the head's [tail] field tracks the chain tail so append
     * is O(1) without per-key allocation. Non-head nodes ignore [tail].
     *
     * **Mutability**: [next] / [tail] are mutated only under the
     * EventLoop's `regMutex`. No `@Volatile` because all access is lock-
     * guarded.
     *
     * @param fd The file descriptor to watch.
     * @param interest Read or write readiness.
     * @param continuation The coroutine to resume when the fd is ready.
     */
    class Registration(
        val fd: Int,
        val interest: Interest,
        val continuation: CancellableContinuation<Unit>,
    ) {
        internal var next: Registration? = null
        internal var tail: Registration? = null
    }

    init {
        val fd = syscallOps.kqueueCreate()
        if (fd < 0) error("kqueue() failed: ${errnoMessage(-fd)}")
        kqFd = fd

        // Create wakeup pipe and register the read end with kqueue
        val pipeErr = syscallOps.makePipe(wakeupFds)
        if (pipeErr != 0) {
            closeFdSafely(kqFd, logger, "kqueue init (pipe failure)")
            error("pipe() failed: ${errnoMessage(pipeErr)}")
        }
        syscallOps.setNonBlocking(wakeupFds[0])
        syscallOps.setNonBlocking(wakeupFds[1])

        val kevErr = syscallOps.addReadFilter(kqFd, wakeupFds[0])
        if (kevErr != 0) {
            closeFdSafely(wakeupFds[0], logger, "kqueue init (kevent failure)")
            closeFdSafely(wakeupFds[1], logger, "kqueue init (kevent failure)")
            closeFdSafely(kqFd, logger, "kqueue init (kevent failure)")
            error("kevent(EV_ADD, wakeupFd) failed: ${errnoMessage(kevErr)}")
        }
    }

    // --- CoroutineDispatcher ---

    /**
     * Dispatches a coroutine block to run on this EventLoop thread.
     *
     * Called by the coroutine machinery when a continuation needs to resume.
     * The block is queued and the EventLoop is woken up to process it
     * in the next loop iteration via [drainTasks].
     */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskQueue.offer(block)
        // Skip wakeup when already on the EventLoop thread — the loop
        // will drain tasks before the next kevent(). pipe write is a
        // syscall; avoiding it on the hot path eliminates unnecessary overhead.
        if (!inEventLoop()) {
            wakeup()
        }
    }

    /**
     * Hands [onLoop] to this EventLoop's thread; runs [ifStopped] on the caller
     * if the loop is already gone. Does not wait for either to finish — see
     * [LoopHandoff.runOnLoop] for why, and for what each block may touch.
     *
     * **Thread safety**: safe from any thread.
     */
    internal fun runOnLoop(onLoop: () -> Unit, ifStopped: () -> Unit = onLoop) {
        handoff.runOnLoop(onLoop, ifStopped)
    }

    /**
     * Returns `true` if the current pthread is this EventLoop's thread.
     * Returns `false` before [loop] has started (engine init phase) or
     * from any other thread.
     */
    internal fun inEventLoop(): Boolean {
        val t = eventLoopThread ?: return false
        return pthread_equal(pthread_self(), t) != 0
    }

    /**
     * Throws [IllegalStateException] if called from a thread other than
     * this EventLoop's pthread. Used to assert EL-thread affinity on
     * internal state transitions that are not guarded by [regMutex]
     * (task queue drain, kevent event processing, etc).
     *
     * Holds unconditionally, including before [loop] runs. It used to return
     * without checking while the thread handle was unset, on the reasoning that
     * only single-threaded construction could get there. That reasoning was
     * wrong: `accept()` builds a transport — and registers its fd — on whatever
     * thread the caller is on, so a registration could reach the submit path
     * from off-loop during the window between `pthread_create` returning and
     * [loop] assigning the handle. [register] and [registerCallback] now funnel
     * through [dispatch] when off-loop, so every caller of a submit path
     * arrives on the EventLoop thread and the check can be absolute.
     *
     * That covers the submit paths, not every `kevent` the engine issues.
     * One place still calls the syscall directly and never reaches this check:
     * this class's constructor registers its own wakeup fd via `addReadFilter`,
     * before the thread it would be checking against exists.
     */
    internal fun assertInEventLoop(operation: String) {
        val t = eventLoopThread
        check(t != null && pthread_equal(pthread_self(), t) != 0) {
            "$operation must run on the EventLoop thread"
        }
    }

    // --- Channel registration ---

    /**
     * Starts the EventLoop thread. Must be called once after construction.
     * The thread runs [loop] until [close] is called.
     */
    fun start() {
        val ref = StableRef.create(this)
        val rc = pthread_create(
            threadPtr.ptr,
            null,
            staticCFunction { arg ->
                val el = arg!!.asStableRef<KqueueEventLoop>().get()
                el.loop()
                arg.asStableRef<KqueueEventLoop>().dispose()
                null
            },
            ref.asCPointer(),
        )
        if (rc != 0) {
            // pthread_create returns the errno-like code directly; errno is not set.
            ref.dispose()
            error("pthread_create() failed: ${errnoMessage(rc)}")
        }
    }

    /**
     * [register]s only if [stillWanted] holds, evaluated under the same lock
     * that [cancelAll] takes.
     *
     * `StreamServer.accept()` must decide "is my server still open?" and append
     * its waiter as one step: `close()` runs [cancelAll], and a registration
     * that lands after it is never resumed. Both sides already serialise on
     * this loop's registration lock, so the check belongs here rather than
     * behind a second mutex the server would have to own — and outlive.
     *
     * [stillWanted] runs **while this loop's registration lock is held**, so it
     * must be a plain state read: taking another lock, or calling back into
     * this loop, can deadlock. `StreamServer` passes a volatile flag read.
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
        if (inEventLoop()) {
            submitAddFilter(fd, interest, key, newReg, cont)
        } else {
            dispatch(EmptyCoroutineContext, Runnable { submitAddFilter(fd, interest, key, newReg, cont) })
        }
        return newReg
    }

    /**
     * Registers a file descriptor for read or write readiness notification.
     *
     * When `kevent()` reports the fd as ready, the head [Registration] of
     * the `(fd, interest)` chain is popped and its continuation is resumed
     * with [Unit]. The caller should retry the I/O operation after being
     * resumed.
     *
     * Multiple coroutines may register on the same `(fd, interest)` key —
     * they form a FIFO chain. Each kevent fire pops one waiter; kqueue's
     * level-triggered persistent `EV_ADD` semantics naturally cascade-fire
     * subsequent waiters while the fd remains readable. This handles the
     * concurrent `accept()` pattern (multiple coroutines on a shared
     * `serverFd`) without losing continuations.
     *
     * The fd is added to kqueue via `EV_ADD` (idempotent — adding to an
     * already-armed filter just refreshes flags, does not duplicate).
     * [wakeup] is called to interrupt `kevent()` if the EventLoop is
     * blocked.
     *
     * @return The newly created [Registration] handle. Pass it to
     *   [unregister] from `invokeOnCancellation` to remove only this
     *   continuation from the chain.
     */
    fun register(fd: Int, interest: Interest, cont: CancellableContinuation<Unit>): Registration {
        val key = registrationKey(fd, interest)
        val newReg = Registration(fd, interest, cont)

        // Append BEFORE arming the kqueue filter to close the race window
        // where kevent fires before the map entry exists. The event loop
        // checks the map under the same lock, so the head Registration is
        // always found.
        withRegLock { appendRegistration(key, newReg) }

        // EventLoop-funneled submission fix (Option D): only the
        // EventLoop thread issues kevent submissions, so a concurrent
        // dispatchReady's stale-filter EV_DELETE cannot race against an
        // EV_ADD from another thread. Matches Netty / libuv's "I/O ops on
        // the I/O thread" model. When register() is called from the
        // EventLoop thread itself (e.g., a chained suspend register from
        // within onReady), submit inline to keep the fast path lock-free.
        // Every other caller dispatches, including one that arrives before
        // [start] — dispatch() queues and loop() drains on its first
        // iteration, so a pre-start registration waits rather than running
        // on whichever thread happened to call.
        if (inEventLoop()) {
            submitAddFilter(fd, interest, key, newReg, cont)
        } else {
            dispatch(EmptyCoroutineContext, Runnable { submitAddFilter(fd, interest, key, newReg, cont) })
        }
        return newReg
    }

    /**
     * EventLoop-thread submission of EV_ADD for [fd]. Resumes [cont] with
     * an exception on failure (after removing [reg] from the chain at [key]).
     *
     * [key] is computed by the caller (`register()`) so the error path
     * does not recompute `registrationKey(fd, interest)`.
     *
     * @param reg The Registration to remove on submit failure.
     */
    private fun submitAddFilter(
        fd: Int,
        interest: Interest,
        key: Long,
        reg: Registration,
        cont: CancellableContinuation<Unit>,
    ) {
        assertInEventLoop("KqueueEventLoop.submitAddFilter")
        // The arm is dispatched after the chain append releases the lock, so a
        // close() can queue its teardown in between: cancelAll then resumes
        // this waiter and the fd is closed before this runs. Arming here would
        // touch a descriptor that is gone — or, once the kernel reuses the
        // number, somebody else's — and leave a ledger entry for it, which is
        // the stale-registration hang the teardown ordering exists to prevent.
        // A waiter no longer in the chain has already been resumed; drop it.
        if (!withRegLock { isRegistered(key, reg) }) return

        val kevErr = when (interest) {
            Interest.READ -> syscallOps.addReadFilter(kqFd, fd)
            Interest.WRITE -> syscallOps.addWriteFilter(kqFd, fd)
        }
        if (kevErr != 0) {
            withRegLock { removeRegistration(key, reg) }
            cont.resumeWithException(
                IllegalStateException("kevent(EV_ADD, fd=$fd) failed: ${errnoMessage(kevErr)}"),
            )
        }
    }

    /**
     * Removes a single [Registration] from its chain. Called from
     * [invokeOnCancellation] when one specific waiter is cancelled.
     * Other waiters on the same `(fd, interest)` key are unaffected.
     */
    fun unregister(reg: Registration) {
        val key = registrationKey(reg.fd, reg.interest)
        withRegLock { removeRegistration(key, reg) }
    }

    /**
     * Cancels every pending [Registration] on the given `(fd, interest)`
     * key, resuming each continuation with [cause]. Used by
     * `StreamServer.close()` to terminate all suspended `accept()` calls
     * in one shot. The kqueue filter is left untouched — callers that own
     * the fd are responsible for `closeFdSafely(fd)` afterward.
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
        for (reg in toResume) reg.continuation.resumeWithException(cause)
    }

    /** True if [reg] is still in the chain for [key]. Caller MUST hold [regMutex]. */
    private fun isRegistered(key: Long, reg: Registration): Boolean {
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

    /**
     * Pops and returns the FIFO head from the chain for [key], or null if
     * the chain is empty. Caller MUST hold [regMutex].
     */
    private fun popHeadRegistration(key: Long): Registration? {
        val head = registrations[key] ?: return null
        val next = head.next
        if (next == null) {
            registrations.remove(key)
        } else {
            // New head inherits tail tracking: if old head pointed at `next`
            // as the tail (chain length 2), the new head IS the tail (null);
            // otherwise pass the existing tail pointer along.
            next.tail = if (head.tail === next) null else head.tail
            registrations[key] = next
        }
        head.next = null
        head.tail = null
        return head
    }

    /** Removes [reg] from the chain for [key] (search by identity). Caller MUST hold [regMutex]. */
    private fun removeRegistration(key: Long, reg: Registration) {
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
     * Registers a callback for fd readiness notification (pipeline / non-suspend path).
     *
     * Unlike [register], this does not use coroutine continuations. When `kevent()`
     * reports the fd as ready, [callback] is invoked directly on the EventLoop thread.
     * The registration is one-shot: removed after the callback fires.
     */
    fun registerCallback(fd: Int, interest: Interest, listener: FdReadyListener) {
        val key = registrationKey(fd, interest)

        // Register callback BEFORE adding to kqueue (same rationale as register()).
        withRegLock {
            callbackRegistrations[key] = listener
        }

        // EventLoop-funneled submission fix (Option D). See [register]
        // for the rationale.
        if (inEventLoop()) {
            submitAddCallbackFilter(fd, interest, key)
        } else {
            dispatch(EmptyCoroutineContext, Runnable { submitAddCallbackFilter(fd, interest, key) })
        }
    }

    /**
     * EventLoop-thread submission of EV_ADD for a callback registration.
     * Removes the callback entry on submit failure (no continuation to
     * resume — the caller must observe the missing event via a higher-
     * level timeout).
     */
    private fun submitAddCallbackFilter(fd: Int, interest: Interest, key: Long) {
        assertInEventLoop("KqueueEventLoop.submitAddCallbackFilter")
        val kevErr = when (interest) {
            Interest.READ -> syscallOps.addReadFilter(kqFd, fd)
            Interest.WRITE -> syscallOps.addWriteFilter(kqFd, fd)
        }
        if (kevErr != 0) {
            withRegLock { callbackRegistrations.remove(key) }
            logger.error {
                "kevent(EV_ADD, fd=$fd, ${interest.name}) for callback failed: " +
                    "${errnoMessage(kevErr)} — readiness callback will not fire"
            }
        }
    }

    /**
     * Removes a pending callback registration for the given fd and interest.
     */
    fun unregisterCallback(fd: Int, interest: Interest) {
        val key = registrationKey(fd, interest)
        withRegLock {
            callbackRegistrations.remove(key)
        }
    }

    // --- Wakeup ---

    /**
     * Wakes up the EventLoop thread by writing 1 byte to the wakeup pipe.
     * Called after [register] or [dispatch] to ensure `kevent()` re-evaluates
     * pending fds and tasks.
     */
    private fun wakeup() {
        val err = syscallOps.wakeupWrite(wakeupFds[1], wakeupWriteBuf)
        // EAGAIN: pipe buffer full — a wakeup is already pending, which
        // is exactly what we want. Benign.
        if (err != 0 && err != EAGAIN) {
            logger.debug { "kqueue wakeup write() failed: ${errnoMessage(err)}" }
        }
    }

    /**
     * Consumes all bytes from the wakeup pipe's read end.
     * Called from the EventLoop thread when the wakeup fd fires.
     */
    private fun consumeWakeup() {
        val err = syscallOps.wakeupDrain(wakeupFds[0], wakeupReadBuf)
        if (err != 0) {
            logger.debug { "kqueue wakeup read() failed: ${errnoMessage(err)}" }
        }
    }

    // --- Event loop ---

    /**
     * The EventLoop's main loop, running on a dedicated pthread.
     *
     * Each iteration:
     * 1. [drainTasks] — execute queued coroutine continuations
     * 2. `kevent()` — wait for fd readiness events (non-blocking if tasks
     *    are pending, blocking otherwise)
     * 3. Process ready fds — resume associated coroutine continuations
     */
    // --- Idle/read deadline timer (progress-bound mechanism) ---

    private val timeOrigin = TimeSource.Monotonic.markNow()

    private fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    /**
     * Per-EventLoop deadline timer backing the transport idle (no-progress) timeout.
     * Confined to this EventLoop thread: transports on this loop schedule / touch /
     * cancel idle deadlines through it, [loop] drives the `kevent` timeout from
     * [DeadlineScheduler.nextDeadlineMillis], and fires due timers via
     * [DeadlineScheduler.expireDue] after each wake.
     */
    internal val deadlineScheduler = DeadlineScheduler(::nowMillis, logger)

    internal fun loop() {
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
            drainTasks()
            handoff.markQuiescent()
        }
    }

    private fun loopBody() {
        while (running.value != 0) {
            drainTasks()

            // Non-blocking poll if tasks arrived during drainTasks(), else block
            // until events / wakeup / the next connection deadline (whichever first).
            val timeout = computeWaitTimeout()
            val n = syscallOps.waitEvents(kqFd, eventBuffer, timeout)
            if (n < 0) {
                // Negative return encodes -errno per KqueueSyscallOps contract.
                val err = -n
                // EINTR: interrupted by signal (e.g. debugger attach).
                // EAGAIN: spurious wakeup. Both are retriable.
                if (err == EINTR || err == EAGAIN) continue
                // Fatal error — log and terminate the EventLoop thread.
                // Cannot throw from a pthread; logger is the only output path.
                logger.error { "kevent() fatal error: ${errnoMessage(err)}" }
                break
            }
            for (i in 0 until n) {
                val ev = eventBuffer[i]
                val fd = ev.fd

                if (fd == wakeupFds[0]) {
                    consumeWakeup()
                    continue
                }

                val interest = when (ev.filter) {
                    EVFILT_READ -> Interest.READ
                    EVFILT_WRITE -> Interest.WRITE
                    else -> continue
                }
                // EV_EOF surfaces peer-FIN / peer-RST regardless of which filter
                // is armed. Pass it to the listener so write-only push clients
                // (`PipelinedChannel.readEnabled = false`) can still detect
                // peer close: see `KqueueIoTransport.onPeerClosed` for how the
                // signal reaches `IoTransport.onReadClosed`.
                val eofFlag = (ev.flags and EV_EOF) != 0
                dispatchReady(fd, interest, eofFlag)
            }
            // Fire any connection idle/read deadlines that elapsed during the wait.
            deadlineScheduler.expireDue(nowMillis())
        }
    }

    /**
     * Computes the `kevent` timeout (ms): `0` if tasks are pending (non-blocking
     * poll), otherwise the time until the nearest connection deadline, or
     * [KqueueSyscallOps.TIMEOUT_BLOCK] to block indefinitely when none is scheduled.
     * A non-positive remaining time clamps to `0` so an already-elapsed deadline is
     * serviced on the next [DeadlineScheduler.expireDue] without blocking.
     */
    private fun computeWaitTimeout(): Long {
        if (hasTasksPending()) return 0L
        val next = deadlineScheduler.nextDeadlineMillis()
        if (next == Long.MAX_VALUE) return KqueueSyscallOps.TIMEOUT_BLOCK
        return (next - nowMillis()).coerceAtLeast(0L)
    }

    /**
     * Dispatches a ready event for [fd] + [interest] to the appropriate handler.
     *
     * Checks callback registrations first (pipeline path), then suspend
     * registrations (Channel path).
     *
     * **Pipeline path**: after [FdReadyListener.onReady] returns, checks whether
     * the callback was re-registered. READ callbacks always re-arm synchronously
     * via `armRead()`, so this check is normally a no-op. WRITE callbacks that
     * complete a successful flush do NOT re-arm; in that case
     * [removeInterestFromKqueue] deletes the `EVFILT_WRITE` filter. Without this,
     * kqueue's persistent `EV_ADD` keeps reporting EVFILT_WRITE on every
     * `kevent()` call — a busy loop that saturates the EventLoop thread when many
     * connections have completed writes.
     *
     * **Suspend path**: after popping one waiter, the filter is removed when the
     * chain empties, because the resumed coroutine may not immediately re-register
     * (unlike Pipeline's synchronous armRead cycle).
     *
     * **Stale-interest safety net**: when neither a callback nor a suspend waiter
     * is found, a WARN is logged and the filter is removed. Without this, a stale
     * filter causes a level-triggered busy loop until the fd is closed.
     */
    private fun dispatchReady(fd: Int, interest: Interest, eofFlag: Boolean) {
        assertInEventLoop("KqueueEventLoop.dispatchReady")
        val key = registrationKey(fd, interest)
        val cb = withRegLock { callbackRegistrations.remove(key) }
        if (cb != null) {
            // Order: drain (onReady) before close (onPeerClosed) for combined
            // data-and-EOF events. For pure EOF (no pending data) the listener
            // can detect "no more data" via the read syscall in onReady — the
            // standard `read()` returns 0 path — so unconditionally calling
            // onReady first keeps the contract simple. The eofFlag dispatch
            // path is for the case where read interest was never armed
            // (`readEnabled = false`) and the only way to surface peer-close
            // is via this engine-side hook.
            cb.onReady(interest)
            if (eofFlag) {
                cb.onPeerClosed(interest)
                // Same re-registration check as the branch below. This used to
                // disarm unconditionally, on the reasoning that a connection
                // reporting EOF is ending and its listener cannot re-register
                // meaningfully. That is not true of every listener that reaches
                // here: a server's AcceptArm re-arms on both WouldBlock and a
                // failed accept, and the re-arm issues no syscall when the
                // filter is already present, so disarming after it discarded a
                // live registration and left an accept loop that never runs
                // again.
                val reRegistered = withRegLock { callbackRegistrations[key] != null }
                if (!reRegistered) {
                    removeInterestFromKqueue(fd, interest)
                }
            } else {
                // Existing stale-filter cleanup path (PR #449): if the callback
                // did not re-register during onReady (e.g., a WRITE callback
                // after a successful flush that does not re-arm), remove the
                // kqueue filter to prevent a stale level-triggered busy loop.
                // READ callbacks always re-arm via armRead() in the normal flow.
                val reRegistered = withRegLock { callbackRegistrations[key] != null }
                if (!reRegistered) {
                    removeInterestFromKqueue(fd, interest)
                }
            }
        } else {
            // Suspend path: pop one waiter from the FIFO chain. If siblings remain
            // (concurrent `accept()` callers waiting on the same serverFd), keep the
            // filter armed so the next kevent() cycle cascade-fires the next sibling.
            // Only when the chain becomes empty do we disarm to avoid busy-loop
            // re-fire while the resumed continuation finishes its async I/O.
            val pair: Pair<Registration?, Boolean> = withRegLock {
                val popped = popHeadRegistration(key)
                popped to (registrations[key] != null)
            }
            val popped = pair.first
            if (popped != null) {
                if (!pair.second) {
                    removeInterestFromKqueue(fd, interest)
                }
                popped.continuation.resume(Unit)
            } else {
                // No handler (no callback, no suspend waiter). The kqueue filter is
                // stale: armed without a corresponding handler, or not removed when
                // the last handler deregistered. The persistent EV_ADD filter re-fires
                // every kevent() call for as long as the fd is ready — a busy loop.
                // Remove it now and log a WARN so the invariant violation is visible.
                logger.warn { "dispatchReady: no handler for fd=$fd ${interest.name} — removing stale kqueue filter" }
                removeInterestFromKqueue(fd, interest)
            }
        }
    }

    /**
     * Removes [fd]'s kqueue filter for [interest] (`EV_DELETE`).
     *
     * Called from [dispatchReady] on both the pipeline path (when a WRITE callback
     * does not re-register, indicating flush success) and the suspend path (when the
     * registration chain empties). Prevents level-triggered busy-loops by removing
     * the filter until the caller arms again.
     */
    private fun removeInterestFromKqueue(fd: Int, interest: Interest) {
        val err = when (interest) {
            Interest.READ -> syscallOps.deleteReadFilter(kqFd, fd)
            Interest.WRITE -> syscallOps.deleteWriteFilter(kqFd, fd)
        }
        if (err != 0) {
            logger.debug {
                "kevent(EV_DELETE, fd=$fd, ${interest.name}) failed: ${errnoMessage(err)}"
            }
        }
    }

    /**
     * Runs all queued coroutine continuations on this thread.
     *
     * Uses a while loop because task execution may enqueue new tasks
     * (e.g., a resumed coroutine calls channel.read() which suspends
     * and re-registers, then immediately resumes via dispatch()).
     * Draining in the same iteration prevents starvation where tasks
     * accumulate faster than kevent() cycles can process them.
     */
    private fun drainTasks() {
        assertInEventLoop("KqueueEventLoop.drainTasks")
        while (true) {
            drainBatch.clear()
            taskQueue.drain(drainBatch)
            if (drainBatch.isEmpty()) return
            // Index-based iteration avoids Iterator allocation on every drain cycle.
            for (i in 0 until drainBatch.size) {
                // Same catch-and-warn guard as the NIO and io_uring drains: a
                // dispatched task that throws (engine-internal teardown / arming
                // Runnables, or a coroutine task whose machinery is not the
                // thrower) must not kill the EventLoop pthread — every channel
                // on this loop dies with it — or skip the remaining tasks in
                // this batch. Coroutine tasks route their body exceptions to
                // their Job before reaching here; this guard is the backstop
                // for everything else.
                try {
                    drainBatch[i].run()
                } catch (t: Throwable) {
                    logger.warn(t) { "dispatched task threw on the EventLoop" }
                }
            }
        }
    }

    /**
     * Checks if there are pending tasks without draining them.
     *
     * Used to decide `kevent()` timeout: 0 if tasks are pending
     * (non-blocking poll), null otherwise (block until events).
     */
    private fun hasTasksPending(): Boolean {
        return taskQueue.isNotEmpty()
    }

    // --- Lifecycle ---

    /**
     * Stops the EventLoop and releases all resources.
     *
     * Signals the EventLoop thread to stop, joins it, then closes the
     * kqueue fd and wakeup pipe fds. Any pending registrations have their
     * continuations left uncompleted (the caller's coroutine will be
     * garbage collected).
     */
    fun close() {
        if (running.compareAndSet(1, 0)) {
            wakeup()
            // Join the EventLoop thread. threadPtr was written by pthread_create.
            val t = threadPtr.ptr[0]
            if (t != null) {
                pthread_join(t, null)
            }
            closeFdSafely(wakeupFds[0], logger, "event loop teardown (wakeupFds[0])")
            closeFdSafely(wakeupFds[1], logger, "event loop teardown (wakeupFds[1])")
            closeFdSafely(kqFd, logger, "event loop teardown (kqFd)")
            val destroyRet = pthread_mutex_destroy(regMutex.ptr)
            if (destroyRet != 0) {
                logger.warn { "pthread_mutex_destroy() failed: ${errnoMessage(destroyRet)}" }
            }
            // taskQueue is MpscQueue (lock-free, no mutex to destroy)
            arena.clear()
            // Close the per-EL allocator child. By construction the
            // EventLoopGroup hands each EL the result of
            // `BufferAllocator.createChild()`, so closing here drains
            // this loop's freelists and runs `Freelist.close()` (mutex
            // destroy / nativeHeap.free for `MutexFreelist`). Safe because
            // the EL thread is joined above — no concurrent allocate /
            // returnToPool calls. Default no-op for `DefaultAllocator` (tests
            // that instantiate this loop with the stateless allocator).
            // Free the shared writev scratch arrays — the loop thread is
            // joined above, so no transport flush can touch them anymore.
            nativeHeap.free(writevBases)
            nativeHeap.free(writevLens)
            allocator.close()
        }
    }

    // --- Helpers ---

    /**
     * Encodes fd + interest into a single Long key.
     * fd in lower 32 bits, interest ordinal in upper 32 bits.
     */
    private fun registrationKey(fd: Int, interest: Interest): Long {
        return fd.toLong() or (interest.ordinal.toLong() shl 32)
    }

    /** Runs [block] under the registration mutex. */
    private inline fun <T> withRegLock(block: () -> T): T {
        pthread_mutex_lock(regMutex.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(regMutex.ptr)
        }
    }

    // --- KqueueSuspendRegister impl (seam for connect InProgress) ---

    override suspend fun awaitWriteReady(fd: Int, logger: Logger) {
        suspendCancellableCoroutine<Unit> { cont ->
            val reg = register(fd, Interest.WRITE, cont)
            cont.invokeOnCancellation {
                unregister(reg)
                closeFdSafely(fd, logger, "connect cancellation")
            }
        }
    }

    companion object {

        /** Initial capacity of the shared writev scratch arrays (grows 1.5x). */
        const val INITIAL_WRITEV_CAPACITY = 8

        /**
         * Maximum events per kevent() call. 64 balances memory usage
         * (64 * sizeof(kevent) = ~2.5 KiB on arm64) against reducing
         * the number of kevent() syscalls under high fd counts.
         * Netty uses 4096; 64 is conservative for initial implementation.
         */
        private const val MAX_EVENTS = 64

        /** Drain buffer size for consumeWakeup(). Matches pipe FIFO default. */
        private const val WAKEUP_DRAIN_SIZE = 64
    }
}
