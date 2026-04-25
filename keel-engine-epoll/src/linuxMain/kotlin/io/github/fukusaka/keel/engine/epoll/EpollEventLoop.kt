package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.MpscQueue
import io.github.fukusaka.keel.collections.LongObjectMap
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.native.posix.closeFdSafely
import io.github.fukusaka.keel.native.posix.errnoMessage
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
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
import kotlin.coroutines.resume

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
        pthread_mutex_init(ptr, null)
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

    private val wakeupFd: Int
    private val running = AtomicInt(1) // 1 = running, 0 = stopped
    private val threadPtr = arena.alloc<pthread_tVar>()
    @kotlin.concurrent.Volatile
    private var eventLoopThread: pthread_t? = null

    /**
     * A pending I/O interest for a file descriptor.
     *
     * @param fd The file descriptor to watch.
     * @param interest Read or write readiness.
     * @param continuation The coroutine to resume when the fd is ready.
     */
    class Registration(
        val fd: Int,
        val interest: Interest,
        val continuation: CancellableContinuation<Unit>,
    )

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
    fun interface FdReadyListener {
        fun onReady(interest: Interest)
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
     * Returns without checking if the EventLoop has not yet started —
     * engine construction runs before [loop] sets the thread handle,
     * and constructor-time initialisation is inherently single-threaded.
     *
     * Matches the pattern established in `IoUringEventLoop.assertInEventLoop`.
     */
    internal fun assertInEventLoop(operation: String) {
        val t = eventLoopThread ?: return
        check(pthread_equal(pthread_self(), t) != 0) {
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
            threadPtr.ptr, null,
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
     * When `epoll_wait()` reports the fd as ready, the [cont] is resumed
     * with [Unit] and the registration is removed (one-shot). The caller
     * should retry the I/O operation after being resumed.
     *
     * The fd is added to epoll via `EPOLL_CTL_ADD` and recorded in
     * [registrations]. [wakeup] is called to interrupt `epoll_wait()`
     * if the EventLoop is blocked.
     */
    fun register(fd: Int, interest: Interest, cont: CancellableContinuation<Unit>) {
        val events = when (interest) {
            Interest.READ -> EPOLLIN
            Interest.WRITE -> EPOLLOUT
        }
        val key = registrationKey(fd, interest)

        // Register continuation BEFORE adding to epoll to close the race window
        // where epoll fires before the map entry exists.
        withRegLock {
            registrations[key] = Registration(fd, interest, cont)
        }

        addOrModifyEpoll(fd, events)
        wakeup()
    }

    /**
     * Removes a pending registration for the given fd and interest.
     * Called from [invokeOnCancellation] when a coroutine is cancelled.
     */
    fun unregister(fd: Int, interest: Interest) {
        val key = registrationKey(fd, interest)
        withRegLock {
            registrations.remove(key)
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
            Interest.READ -> EPOLLIN
            Interest.WRITE -> EPOLLOUT
        }
        val key = registrationKey(fd, interest)

        withRegLock {
            callbackRegistrations[key] = listener
        }

        addOrModifyEpoll(fd, events)
        wakeup()
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
    internal fun loop() {
        eventLoopThread = pthread_self()
        while (running.value != 0) {
            drainTasks()

            // Non-blocking poll if tasks arrived during drainTasks(),
            // otherwise block until events or wakeup.
            // epoll_wait timeout: 0 = immediate, -1 = indefinite block.
            val timeout = if (hasTasksPending()) 0 else EpollSyscallOps.TIMEOUT_BLOCK
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
                val evFlags = ev.events
                if (evFlags and EPOLLIN != 0) {
                    dispatchReady(fd, Interest.READ)
                }
                if (evFlags and EPOLLOUT != 0) {
                    dispatchReady(fd, Interest.WRITE)
                }
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
     * accumulate faster than epoll_wait() cycles can process them.
     */
    private fun drainTasks() {
        assertInEventLoop("EpollEventLoop.drainTasks")
        while (true) {
            drainBatch.clear()
            taskQueue.drain(drainBatch)
            if (drainBatch.isEmpty()) return
            for (task in drainBatch) {
                task.run()
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
            pthread_mutex_destroy(regMutex.ptr)
            // taskQueue is MpscQueue (lock-free, no mutex to destroy)
            arena.clear()
        }
    }

    // --- Helpers ---

    /**
     * Dispatches a ready event for [fd] + [interest] to the appropriate handler.
     *
     * Checks callback registrations first (pipeline path), then suspend
     * registrations (Channel path). Does NOT call epoll_ctl to remove the
     * interest — level-triggered epoll will re-fire, but the handler's
     * [armRead]/[registerCallback] re-registers the callback before the next
     * epoll_wait, so no spurious wakeup occurs.
     *
     * For suspend registrations (Channel path), the interest is removed from
     * [fdEvents] and epoll is updated via MOD, because the coroutine may not
     * immediately re-register (unlike Pipeline's synchronous armRead cycle).
     */
    private fun dispatchReady(fd: Int, interest: Interest) {
        assertInEventLoop("EpollEventLoop.dispatchReady")
        val key = registrationKey(fd, interest)
        val cb = withRegLock { callbackRegistrations.remove(key) }
        if (cb != null) {
            // Pipeline path: callback re-arms synchronously (armRead inside
            // handler chain), so fdEvents stays as-is — no epoll_ctl needed.
            cb.onReady(interest)
        } else {
            val reg = withRegLock { registrations.remove(key) }
            if (reg != null) {
                // Suspend path: coroutine resumes asynchronously, so remove
                // the interest from epoll to prevent busy-loop re-fire.
                removeInterestFromEpoll(fd, interest)
                reg.continuation.resume(Unit)
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
     * Called only from the suspend path in [dispatchReady] to prevent level-triggered
     * busy-loop when the coroutine has not yet re-registered. Pipeline callbacks
     * skip this because they re-arm synchronously before returning to epoll_wait.
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
            register(fd, Interest.WRITE, cont)
            cont.invokeOnCancellation {
                unregister(fd, Interest.WRITE)
                closeFdSafely(fd, logger, "connect cancellation")
            }
        }
    }

    companion object {
        /**
         * Maximum events per epoll_wait() call. 64 balances memory usage
         * (64 * sizeof(epoll_event) = ~768 bytes on x86_64) against
         * reducing the number of epoll_wait() syscalls under high fd counts.
         */
        private const val MAX_EVENTS = 64
    }
}
