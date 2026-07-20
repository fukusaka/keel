package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.MpscQueue
import io.github.fukusaka.keel.collections.LongObjectMap
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
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
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.linux.EPOLLRDHUP
import platform.posix.EAGAIN
import platform.posix.EEXIST
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
 * Single-threaded epoll event loop for Linux, also serving as a [CoroutineDispatcher].
 *
 * Drives all I/O for channels created by [EpollEngine]. A dedicated
 * pthread runs [loop], interleaving three tasks:
 * 1. Execute queued coroutine continuations ([taskQueue])
 * 2. Call `epoll_wait()` to wait for fd readiness events
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
 * **Wakeup mechanism**: An `eventfd(2)` is registered with epoll.
 * External threads call [wakeup] to signal the eventfd, causing
 * `epoll_wait()` to return immediately so the EventLoop can process
 * newly registered fds or queued tasks. eventfd is more efficient than
 * pipe(2) on Linux: single fd instead of two, and kernel-optimized
 * for signaling.
 *
 * **Scalability**: Each EventLoop instance is single-threaded.
 * [EpollEventLoopGroup] creates multiple instances and distributes
 * channels in round-robin for multi-threaded I/O.
 *
 * **Thread safety**: [registrations] is protected by `pthread_mutex_t`.
 * [taskQueue] uses a lock-free MPSC queue ([MpscQueue]) — CAS-based
 * enqueue (~5-10ns) replaces mutex lock/unlock (~50-100ns) on the
 * dispatch hot path.
 *
 * ```
 * EventLoop thread (single loop iteration):
 *   1. drainTasks()        — run coroutine continuations
 *   2. epoll_wait(timeout) — block until events or wakeup
 *      timeout = 0 if tasks pending, -1 otherwise
 *   3. for each ready fd:
 *        if eventfd: consume, continue
 *        lookup registration -> remove -> continuation.resume(Unit)
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollEventLoop(
    internal val logger: Logger,
    /**
     * Per-EventLoop [BufferAllocator] instance. Co-located with the loop
     * (rather than tracked separately in [EpollEventLoopGroup]) so callers
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
     * value. When `true` (default), [EpollIoTransport.flush] schedules the
     * actual send onto the next EL tick via [dispatch] so that same-tick
     * per-emit `requestFlush` calls collapse into one `writev(2)`. When
     * `false`, each `flush()` sends immediately (pre-#900 behaviour).
     */
    val flushCoalescing: Boolean = true,
    private val syscallOps: EpollSyscallOps = PosixEpollSyscallOps,
) : CoroutineDispatcher(), EpollSuspendRegister {

    /**
     * The epoll file descriptor, created at construction.
     * Exposed for [EpollEngine.bind] to register server fds directly
     * via `epoll_ctl(epFd, ...)`. Channel fds are registered via [register].
     */
    val epFd: Int

    // Arena for long-lived native allocations (mutexes).
    // Freed in close().
    private val arena = Arena()

    // Registration mutex protects the registrations map.
    // Task queue uses lock-free MPSC queue — CAS-based enqueue (~5-10ns)
    // replaces mutex lock/unlock (~50-100ns) on the dispatch hot path.
    private val regMutex = arena.alloc<pthread_mutex_t>().apply {
        val initRet = pthread_mutex_init(ptr, null)
        check(initRet == 0) { "pthread_mutex_init() failed: ${errnoMessage(initRet)}" }
    }
    private val registrations = LongObjectMap<Registration>()

    // Callback registrations for pipeline (non-suspend) I/O.
    // Listener interface (instead of `() -> Unit`) lets each `IoTransport`
    // pass `this` to [registerCallback], avoiding per-call lambda allocation
    // on the read re-arm fast path. Mirrors the `Job : DisposableHandle`
    // precedent from kotlinx.coroutines.
    private val callbackRegistrations = LongObjectMap<FdReadyListener>()

    // Tracks the current epoll events per fd. epoll manages fds (not fd+interest
    // pairs), so ADD/MOD must specify all active interest bits at once.
    private val fdEvents = mutableMapOf<Int, Int>()

    // Lock-free MPSC queue replaces pthread_mutex + MutableList for
    // dispatch hot path. CAS (~5-10ns) vs mutex lock/unlock (~50-100ns).
    private val taskQueue = MpscQueue<Runnable>()

    // Reusable scratch buffer for [drainTasks]. Kept as a field so the
    // EventLoop hot path does not allocate a new list each iteration.
    // Only accessed from the EventLoop thread (via [drainTasks]).
    private val drainBatch: MutableList<Runnable> = mutableListOf()

    // Pre-allocated event carrier array reused across every [loop]
    // iteration. Sized to [MAX_EVENTS]; each slot is a mutable [EpEvent]
    // whose fields are overwritten by [EpollSyscallOps.waitEvents].
    // Only accessed from the EventLoop thread.
    private val eventBuffer: Array<EpEvent> = Array(MAX_EVENTS) { EpEvent() }

    /**
     * Shared gather-write scratch: one native (bases, lens) pair reused by
     * every transport flush on this loop (see [EpollIoTransport.flushGather]). Kept per
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

    private val wakeupFd: Int
    private val running = AtomicInt(1) // 1 = running, 0 = stopped
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

    enum class Interest { READ, WRITE }

    /**
     * Listener for fd readiness events on the pipeline (non-suspend) path.
     *
     * Implemented by [io.github.fukusaka.keel.engine.epoll.EpollIoTransport]
     * (and other consumers of [registerCallback]) so the receiver can pass
     * `this` as the listener — eliminating the per-call lambda allocation
     * on the read re-arm fast path. The `interest` parameter lets a single
     * implementation dispatch read vs. write callbacks without separate
     * sub-listener objects.
     */
    /**
     * Listener for epoll readiness events on a registered fd.
     *
     * Two callbacks separate the orthogonal concerns of normal readiness and
     * peer-close detection. Listeners that only care about one side leave the
     * other as the default no-op:
     * - `EpollPipelinedStreamServer` overrides only [onReady] — server fd
     *   teardown is driven by `server.close()` rather than peer-FIN.
     * - `EpollIoTransport` overrides both — the EOF path is what fires
     *   `onReadClosed` to user code even when read interest was never armed
     *   (`readEnabled = false` write-only push client).
     *
     * The dispatch contract is documented on [dispatchReady]: for combined
     * data-and-EOF events the engine calls [onReady] first (so the listener
     * can drain the final bytes) and then [onPeerClosed]. Mirrors the shape
     * established on `KqueueEventLoop`.
     */
    interface FdReadyListener {
        /** Ready for [interest]: data available (READ), space available (WRITE). */
        fun onReady(interest: Interest)

        /**
         * Peer FIN/RST observed via `EPOLLHUP` / `EPOLLERR` / `EPOLLRDHUP`.
         * Default no-op — only listeners that need to surface peer-close to
         * higher layers override (e.g. transports must fire `onReadClosed`).
         *
         * The engine unconditionally removes the epoll interest after this
         * returns, so the listener does not need to disarm explicitly.
         */
        fun onPeerClosed(interest: Interest) {}
    }

    init {
        val fd = syscallOps.epollCreate()
        if (fd < 0) error("epoll_create1() failed: ${errnoMessage(-fd)}")
        epFd = fd

        // Create eventfd for wakeup and register with epoll.
        // eventfd is more efficient than pipe on Linux: single fd,
        // kernel-optimized for event signaling.
        val wf = syscallOps.eventfdCreate()
        if (wf < 0) {
            closeFdSafely(epFd, logger, "epoll init (eventfd failure)")
            error("eventfd() failed: ${errnoMessage(-wf)}")
        }
        wakeupFd = wf

        val ctlErr = syscallOps.epollAdd(epFd, wakeupFd, EPOLLIN)
        if (ctlErr != 0) {
            closeFdSafely(wakeupFd, logger, "epoll init (epoll_ctl failure)")
            closeFdSafely(epFd, logger, "epoll init (epoll_ctl failure)")
            error("epoll_ctl(ADD, wakeupFd) failed: ${errnoMessage(ctlErr)}")
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
        // will drain tasks before the next epoll_wait(). eventfd write is a
        // syscall; avoiding it on the hot path eliminates unnecessary overhead.
        if (!inEventLoop()) {
            wakeup()
        }
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
     * (task queue drain, epoll_wait event processing, etc).
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
     * That covers the submit paths, not every `epoll_ctl` the engine issues. Two
     * places call the syscall directly and never reach this check: this class's
     * constructor registers its own wakeup fd via `epollAdd`, and `bind` adds the
     * server fd to the boss loop.
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
                val el = arg!!.asStableRef<EpollEventLoop>().get()
                el.loop()
                arg.asStableRef<EpollEventLoop>().dispose()
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
     * Registers a file descriptor for read or write readiness notification.
     *
     * When `epoll_wait()` reports the fd as ready, the head [Registration]
     * of the `(fd, interest)` chain is popped and its continuation is
     * resumed with [Unit]. The caller should retry the I/O operation
     * after being resumed.
     *
     * Multiple coroutines may register on the same `(fd, interest)` key —
     * they form a FIFO chain. Each epoll_wait fire pops one waiter;
     * epoll's level-triggered semantics naturally cascade-fire subsequent
     * waiters while the fd remains ready. This handles the concurrent
     * `accept()` pattern (multiple coroutines on a shared `serverFd`)
     * without losing continuations.
     *
     * The fd is added to epoll via `EPOLL_CTL_ADD` (or `MOD` if already
     * armed) on the EventLoop thread. If `register()` is called from
     * another thread the `epoll_ctl` syscall is dispatched to the EL —
     * see [submitAddOrModifyEpoll].
     *
     * @return The newly created [Registration] handle. Pass it to
     *   [unregister] from `invokeOnCancellation` to remove only this
     *   continuation from the chain.
     */
    fun register(fd: Int, interest: Interest, cont: CancellableContinuation<Unit>): Registration {
        val events = when (interest) {
            Interest.READ -> EPOLLIN
            Interest.WRITE -> EPOLLOUT
        }
        val key = registrationKey(fd, interest)
        val newReg = Registration(fd, interest, cont)

        // Append BEFORE arming epoll to close the race window where
        // epoll fires before the chain entry exists.
        withRegLock { appendRegistration(key, newReg) }

        // Funnel the epoll_ctl submission to the owning EventLoop
        // thread. Same idiom as KqueueEventLoop.register (#509) and the
        // libuv / Netty `if (inEventLoop) inline else execute` pattern:
        // every fd-registration syscall runs on a single thread per
        // loop so concurrent EL-thread `EPOLL_CTL_DEL` (issued from
        // dispatchReady for a stale event) cannot reorder against a
        // user-thread `EPOLL_CTL_ADD` for the same fd. A caller arriving
        // before [start] dispatches like any other off-loop caller —
        // dispatch() queues and loop() drains on its first iteration, so
        // the registration waits for the loop instead of running on
        // whichever thread happened to call.
        if (inEventLoop()) {
            submitAddOrModifyEpoll(fd, events)
        } else {
            dispatch(EmptyCoroutineContext, Runnable { submitAddOrModifyEpoll(fd, events) })
        }
        return newReg
    }

    /**
     * EventLoop-thread submission of `EPOLL_CTL_ADD` / `EPOLL_CTL_MOD`
     * for [fd] with the requested [events]. Wraps [addOrModifyEpoll]
     * with the [assertInEventLoop] contract.
     *
     * The `wakeup()` that earlier sat right after `addOrModifyEpoll`
     * in [register] / [registerCallback] is no longer needed: the
     * cross-thread caller path goes through [dispatch] (which performs
     * the eventfd write itself when not in the EL), and the
     * in-EventLoop / engine-init paths do not need to interrupt
     * `epoll_wait` because the loop will iterate naturally on the next
     * pass.
     */
    private fun submitAddOrModifyEpoll(fd: Int, events: Int) {
        assertInEventLoop("EpollEventLoop.submitAddOrModifyEpoll")
        addOrModifyEpoll(fd, events)
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
     * in one shot. The epoll filter is left untouched — callers that own
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
     * When `epoll_wait()` reports the fd as ready, [callback] is invoked directly
     * on the EventLoop thread. The registration is one-shot.
     */
    fun registerCallback(fd: Int, interest: Interest, listener: FdReadyListener) {
        val events = when (interest) {
            // Request EPOLLRDHUP alongside EPOLLIN so peer-shutdown of the
            // read side (graceful FIN) is delivered explicitly. Without it,
            // the kernel delivers EPOLLIN on FIN (read returns 0) but
            // EPOLLHUP only fires for full hangup (both directions closed).
            // dispatchReady's eofFlag check covers HUP / ERR / RDHUP, so the
            // listener's onPeerClosed is invoked on graceful peer-FIN.
            Interest.READ -> EPOLLIN or EPOLLRDHUP
            Interest.WRITE -> EPOLLOUT
        }
        val key = registrationKey(fd, interest)

        withRegLock {
            callbackRegistrations[key] = listener
        }

        // Same funnel idiom as [register] — see its KDoc for rationale.
        if (inEventLoop()) {
            submitAddOrModifyEpoll(fd, events)
        } else {
            dispatch(EmptyCoroutineContext, Runnable { submitAddOrModifyEpoll(fd, events) })
        }
    }

    /** Removes a pending callback registration. */
    fun unregisterCallback(fd: Int, interest: Interest) {
        val key = registrationKey(fd, interest)
        withRegLock {
            callbackRegistrations.remove(key)
        }
    }

    /**
     * Removes all tracking state for [fd] from [fdEvents].
     *
     * Called when a channel closes its fd. Prevents stale entries from
     * accumulating if the OS recycles the fd number for a new socket.
     * Does NOT call `epoll_ctl(DEL)` — closing the fd automatically
     * removes it from epoll.
     */
    fun cleanupFd(fd: Int) {
        withRegLock {
            fdEvents.remove(fd)
        }
    }

    // --- Wakeup ---

    /**
     * Wakes up the EventLoop thread by signaling the eventfd.
     * Called after [register] or [dispatch] to ensure `epoll_wait()`
     * re-evaluates pending fds and tasks.
     */
    private fun wakeup() {
        val err = syscallOps.eventfdWakeupWrite(wakeupFd)
        // EAGAIN: eventfd counter saturated — a wakeup is already
        // pending in the kernel, which is exactly what we want. Benign.
        if (err != 0 && err != EAGAIN) {
            logger.debug { "eventfd_write() failed: ${errnoMessage(err)}" }
        }
    }

    /**
     * Consumes the eventfd counter to reset it.
     * Called from the EventLoop thread when the eventfd fires.
     */
    private fun consumeWakeup() {
        val err = syscallOps.eventfdWakeupDrain(wakeupFd)
        if (err != 0) {
            logger.debug { "eventfd_read() failed: ${errnoMessage(err)}" }
        }
    }

    // --- Event loop ---

    /**
     * The EventLoop's main loop, running on a dedicated pthread.
     *
     * Each iteration:
     * 1. [drainTasks] — execute queued coroutine continuations
     * 2. `epoll_wait()` — wait for fd readiness events (non-blocking if
     *    tasks are pending, blocking otherwise)
     * 3. Process ready fds — resume associated coroutine continuations
     */
    // --- Idle/read deadline timer (progress-bound mechanism) ---

    private val timeOrigin = TimeSource.Monotonic.markNow()

    private fun nowMillis(): Long = timeOrigin.elapsedNow().inWholeMilliseconds

    /**
     * Per-EventLoop deadline timer backing the transport idle (no-progress) timeout.
     * Confined to this EventLoop thread: transports on this loop schedule / touch /
     * cancel idle deadlines through it, [loop] drives the `epoll_wait` timeout from
     * [DeadlineScheduler.nextDeadlineMillis], and fires due timers via
     * [DeadlineScheduler.expireDue] after each wake.
     */
    internal val deadlineScheduler = DeadlineScheduler(::nowMillis, logger)

    internal fun loop() {
        eventLoopThread = pthread_self()
        while (running.value != 0) {
            drainTasks()

            // Non-blocking poll if tasks arrived during drainTasks(), else block
            // until events / wakeup / the next connection deadline (whichever first).
            val timeout = computeWaitTimeout()
            val n = syscallOps.waitEvents(epFd, eventBuffer, timeout)
            if (n < 0) {
                // Negative return encodes -errno per EpollSyscallOps contract.
                val err = -n
                // EINTR: interrupted by signal (e.g. debugger attach).
                // EAGAIN: spurious wakeup. Both are retriable.
                if (err == EINTR || err == EAGAIN) continue
                // Fatal error — log and terminate the EventLoop thread.
                // Cannot throw from a pthread; logger is the only output path.
                logger.error { "epoll_wait() fatal error: ${errnoMessage(err)}" }
                break
            }
            for (i in 0 until n) {
                val ev = eventBuffer[i]
                val fd = ev.fd

                if (fd == wakeupFd) {
                    consumeWakeup()
                    continue
                }

                // Process both EPOLLIN and EPOLLOUT if both are set.
                //
                // EPOLLERR / EPOLLHUP are reported by the kernel regardless of
                // the interest mask (man epoll_ctl: "EPOLLERR / EPOLLHUP will
                // always be reported"). On peer FIN / RST the kernel may fire
                // EPOLLHUP without EPOLLIN on this socket — without dispatching
                // these flags as READ ready, the per-fd Pipeline callback
                // never observes the EOF, the read handler never invokes
                // [IoTransport.onReadClosed], the keep-alive loop hangs in
                // its parser, and connections pile up in CLOSE-WAIT. Mapping
                // HUP/ERR onto the READ branch lets the handler call read()
                // which returns 0 and triggers onReadClosed → propagateInactive
                // → bridge close → keep-alive loop exits → finally cleanup.
                val evFlags = ev.events
                val readReady = (evFlags and (EPOLLIN or EPOLLERR or EPOLLHUP)) != 0
                val writeReady = (evFlags and EPOLLOUT) != 0
                // Surface peer-FIN / peer-RST so listeners can fire
                // onReadClosed even when read interest was never armed by user
                // code (`PipelinedChannel.readEnabled = false`). EPOLLRDHUP
                // is the explicit read-side hangup; EPOLLHUP / EPOLLERR are
                // catch-all hangup / error states.
                val eofFlag = (evFlags and (EPOLLHUP or EPOLLERR or EPOLLRDHUP)) != 0
                if (readReady) {
                    dispatchReady(fd, Interest.READ, eofFlag)
                }
                if (writeReady) {
                    // EOF flag also propagates to WRITE dispatch so a write
                    // callback can choose to surface peer-close, but the
                    // primary EOF path is via READ.
                    dispatchReady(fd, Interest.WRITE, eofFlag)
                }
            }
            // Fire any connection idle/read deadlines that elapsed during the wait.
            deadlineScheduler.expireDue(nowMillis())
        }
    }

    /**
     * Computes the `epoll_wait` timeout (ms): `0` if tasks are pending (non-blocking
     * poll), otherwise the time until the nearest connection deadline, or
     * [EpollSyscallOps.TIMEOUT_BLOCK] (-1) to block indefinitely when no deadline is
     * scheduled. A non-positive remaining time clamps to `0` so an already-elapsed
     * deadline is serviced on the next [DeadlineScheduler.expireDue] without blocking.
     */
    private fun computeWaitTimeout(): Int {
        if (hasTasksPending()) return 0
        val next = deadlineScheduler.nextDeadlineMillis()
        if (next == Long.MAX_VALUE) return EpollSyscallOps.TIMEOUT_BLOCK
        val remaining = next - nowMillis()
        return when {
            remaining <= 0L -> 0
            remaining > Int.MAX_VALUE.toLong() -> Int.MAX_VALUE
            else -> remaining.toInt()
        }
    }

    /**
     * Runs all queued coroutine continuations on this thread.
     *
     * Uses a while loop because task execution may enqueue new tasks
     * (e.g., a resumed coroutine calls channel.read() which suspends
     * and re-registers, then immediately resumes via dispatch()).
     * Draining in the same iteration prevents starvation where tasks
     * accumulate faster than epoll_wait() cycles can process them.
     */
    private fun drainTasks() {
        assertInEventLoop("EpollEventLoop.drainTasks")
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
     * Used to decide `epoll_wait()` timeout: 0 if tasks are pending
     * (non-blocking poll), -1 otherwise (block until events).
     */
    private fun hasTasksPending(): Boolean {
        return taskQueue.isNotEmpty()
    }

    // --- Lifecycle ---

    /**
     * Stops the EventLoop and releases all resources.
     *
     * Signals the EventLoop thread to stop, joins it, then closes the
     * epoll fd and eventfd. Any pending registrations have their
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
            closeFdSafely(wakeupFd, logger, "event loop teardown (wakeupFd)")
            closeFdSafely(epFd, logger, "event loop teardown (epFd)")
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
     * Dispatches a ready event for [fd] + [interest] to the appropriate handler.
     *
     * Checks callback registrations first (pipeline path), then suspend
     * registrations (Channel path).
     *
     * **Pipeline path**: after [FdReadyListener.onReady] returns, checks whether
     * the callback was re-registered. READ callbacks always re-arm synchronously
     * via `armRead()`, so the check is a no-op (fast lock + map lookup, no
     * epoll_ctl). WRITE callbacks that complete a successful flush do NOT re-arm;
     * in that case [removeInterestFromEpoll] is called to clear EPOLLOUT from
     * the epoll filter. Without this, level-triggered epoll keeps reporting
     * EPOLLOUT on every wait iteration — a busy loop that saturates the
     * EventLoop thread when many connections have completed writes.
     *
     * **Suspend path**: the interest is removed from [fdEvents] and epoll is
     * updated via MOD when the chain empties, because the coroutine may not
     * immediately re-register (unlike Pipeline's synchronous armRead cycle).
     *
     * **Stale-interest safety net**: when neither a callback nor a suspend
     * waiter is found, a WARN is logged and the interest is removed from epoll.
     * Without this, a stale interest left in [fdEvents] would cause a
     * level-triggered busy loop until the fd is closed.
     */
    private fun dispatchReady(fd: Int, interest: Interest, eofFlag: Boolean) {
        assertInEventLoop("EpollEventLoop.dispatchReady")
        val key = registrationKey(fd, interest)
        val cb = withRegLock { callbackRegistrations.remove(key) }
        if (cb != null) {
            // Order: drain (onReady) before close (onPeerClosed) for combined
            // data-and-EOF events. For pure EOF the listener detects it via
            // read syscall returning 0 inside onReady; the eofFlag dispatch
            // path is the engine-side fallback when read interest was never
            // armed (`readEnabled = false` write-only push client). Mirrors
            // the dispatch shape established on KqueueEventLoop.
            cb.onReady(interest)
            if (eofFlag) {
                cb.onPeerClosed(interest)
                // EOF path always removes the filter; the listener cannot
                // re-register meaningfully (the connection is ending).
                removeInterestFromEpoll(fd, interest)
            } else {
                // Stale-filter cleanup path: if the callback did not
                // re-register during onReady (e.g., a WRITE callback after
                // a successful flush that does not re-arm), remove the
                // interest from epoll to prevent a stale level-triggered
                // busy loop. READ callbacks always re-arm via armRead() in
                // the normal flow.
                val reRegistered = withRegLock { callbackRegistrations[key] != null }
                if (!reRegistered) {
                    removeInterestFromEpoll(fd, interest)
                }
            }
        } else {
            // Suspend path: pop one waiter from the FIFO chain. If
            // siblings remain (concurrent `accept()` callers waiting on
            // the same serverFd), keep the epoll filter armed so the
            // next epoll_wait cycle cascade-fires the next sibling — the
            // chain drains across successive iterations as the kernel
            // listen queue (or whatever level-triggered condition holds)
            // stays ready. Only when the chain becomes empty do we
            // disarm to avoid busy-loop re-fire while the resumed
            // continuation finishes its asynchronous I/O on another
            // dispatcher.
            // Pair<popped Registration?, chain still has waiters?>
            val pair: Pair<Registration?, Boolean> = withRegLock {
                val popped = popHeadRegistration(key)
                popped to (registrations[key] != null)
            }
            val popped = pair.first
            if (popped != null) {
                if (!pair.second) {
                    removeInterestFromEpoll(fd, interest)
                }
                popped.continuation.resume(Unit)
            } else {
                // No handler (no callback, no suspend waiter). The epoll interest
                // is stale: an interest was armed without a corresponding handler, or
                // was not removed when the last handler deregistered. Level-triggered
                // epoll re-fires every wait iteration for as long as the fd is ready —
                // a busy loop. Remove the stale interest now and emit a WARN so the
                // invariant violation is immediately observable in logs.
                logger.warn { "dispatchReady: no handler for fd=$fd ${interest.name} — removing stale epoll interest" }
                removeInterestFromEpoll(fd, interest)
            }
        }
    }

    /**
     * Adds [newEvents] (EPOLLIN or EPOLLOUT) to the epoll registration for [fd].
     *
     * Uses EPOLL_CTL_ADD for the first registration. If the fd is already
     * registered (EEXIST), falls back to EPOLL_CTL_MOD with the combined events.
     * Skips epoll_ctl entirely when the requested events are already active
     * (e.g., re-arming READ after a Pipeline callback — zero syscall overhead).
     */
    private fun addOrModifyEpoll(fd: Int, newEvents: Int) {
        val (combined, changed) = withRegLock {
            val current = fdEvents[fd] ?: 0
            val merged = current or newEvents
            fdEvents[fd] = merged
            merged to (merged != current)
        }
        if (!changed) return // same interest already registered — skip epoll_ctl
        val addErr = syscallOps.epollAdd(epFd, fd, combined)
        if (addErr == 0) return
        if (addErr == EEXIST) {
            val modErr = syscallOps.epollMod(epFd, fd, combined)
            if (modErr != 0) {
                logger.debug { "epoll_ctl(MOD, fd=$fd) fallback failed: ${errnoMessage(modErr)}" }
            }
        } else {
            // ENOSPC / EBADF / EPERM etc. — unexpected for an fd that
            // was just opened by the engine. Log for diagnostics.
            logger.debug { "epoll_ctl(ADD, fd=$fd) failed: ${errnoMessage(addErr)}" }
        }
    }

    /**
     * Removes a specific interest (EPOLLIN or EPOLLOUT) from the epoll registration for [fd].
     *
     * Called from [dispatchReady] on both the pipeline path (when a WRITE callback
     * does not re-register, indicating flush success) and the suspend path (when the
     * registration chain empties). Prevents level-triggered busy-loops by removing
     * the interest until the caller arms again.
     */
    private fun removeInterestFromEpoll(fd: Int, interest: Interest) {
        val removeBit = when (interest) {
            Interest.READ -> EPOLLIN
            Interest.WRITE -> EPOLLOUT
        }
        val remaining = withRegLock {
            val current = fdEvents[fd] ?: 0
            val updated = current and removeBit.inv()
            if (updated == 0) {
                fdEvents.remove(fd)
            } else {
                fdEvents[fd] = updated
            }
            updated
        }
        val err = syscallOps.epollMod(epFd, fd, remaining)
        if (err != 0) {
            logger.debug {
                "epoll_ctl(MOD, fd=$fd, remove ${interest.name}) failed: ${errnoMessage(err)}"
            }
        }
    }

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

    // --- EpollSuspendRegister impl (seam for connect InProgress) ---

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
         * Maximum events per epoll_wait() call. 64 balances memory usage
         * (64 * sizeof(epoll_event) = ~768 bytes on x86_64) against
         * reducing the number of epoll_wait() syscalls under high fd counts.
         */
        private const val MAX_EVENTS = 64
    }
}
