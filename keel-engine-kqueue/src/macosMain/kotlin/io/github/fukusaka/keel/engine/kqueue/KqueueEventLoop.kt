package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.MpscQueue
import io.github.fukusaka.keel.collections.LongObjectMap
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.native.posix.PosixNativeSocketOps
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
import platform.darwin.EVFILT_READ
import platform.darwin.EVFILT_WRITE
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
 *        lookup registration -> remove -> continuation.resume(Unit)
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueEventLoop(
    internal val logger: Logger,
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
        pthread_mutex_init(ptr, null)
    }
    private val registrations = LongObjectMap<Registration>()
    // Callback registrations for pipeline (non-suspend) I/O.
    // Separated from coroutine registrations to avoid sealed-class overhead.
    private val callbackRegistrations = LongObjectMap<() -> Unit>()

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

    private val wakeupFds = IntArray(2) // [readFd, writeFd]
    // Cached byte arrays to avoid per-wakeup allocation.
    // wakeup() is called once per dispatch/register, so reuse matters.
    private val wakeupWriteBuf = byteArrayOf(1)
    private val wakeupReadBuf = ByteArray(WAKEUP_DRAIN_SIZE)
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
        PosixNativeSocketOps.setNonBlocking(wakeupFds[0])
        PosixNativeSocketOps.setNonBlocking(wakeupFds[1])

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
     * Registers a file descriptor for read or write readiness notification.
     *
     * When `kevent()` reports the fd as ready, the [cont] is resumed with
     * [Unit] and the registration is removed (one-shot). The caller should
     * retry the I/O operation after being resumed.
     *
     * The fd is added to kqueue via `EV_ADD` and recorded in [registrations].
     * [wakeup] is called to interrupt `kevent()` if the EventLoop is blocked.
     */
    fun register(fd: Int, interest: Interest, cont: CancellableContinuation<Unit>) {
        val key = registrationKey(fd, interest)

        // Register continuation BEFORE adding to kqueue to close the race window
        // where kevent fires before the map entry exists. The event loop checks
        // the map under the same lock, so the continuation is always found.
        withRegLock {
            registrations[key] = Registration(fd, interest, cont)
        }

        val kevErr = when (interest) {
            Interest.READ -> syscallOps.addReadFilter(kqFd, fd)
            Interest.WRITE -> syscallOps.addWriteFilter(kqFd, fd)
        }
        if (kevErr != 0) {
            // kevent(EV_ADD) failed — remove the stale map entry and fail the
            // caller's suspend with an exception. Without this, the continuation
            // would never resume (the registration exists but is never dispatched).
            // TODO(v1.0 前): proper engine-level exception type. IllegalStateException
            // is a placeholder; the design for a PosixException / EventLoopException
            // hierarchy is deferred to a separate task.
            withRegLock { registrations.remove(key) }
            cont.resumeWithException(
                IllegalStateException("kevent(EV_ADD, fd=$fd) failed: ${errnoMessage(kevErr)}"),
            )
            return
        }
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
     * Unlike [register], this does not use coroutine continuations. When `kevent()`
     * reports the fd as ready, [callback] is invoked directly on the EventLoop thread.
     * The registration is one-shot: removed after the callback fires.
     */
    fun registerCallback(fd: Int, interest: Interest, callback: () -> Unit) {
        val key = registrationKey(fd, interest)

        // Register callback BEFORE adding to kqueue (same rationale as register()).
        withRegLock {
            callbackRegistrations[key] = callback
        }

        val kevErr = when (interest) {
            Interest.READ -> syscallOps.addReadFilter(kqFd, fd)
            Interest.WRITE -> syscallOps.addWriteFilter(kqFd, fd)
        }
        if (kevErr != 0) {
            // kevent(EV_ADD) failed — remove the stale callback entry. There is
            // no continuation to resume here, so the error is logged and the
            // caller must handle the missing readiness notification.
            withRegLock { callbackRegistrations.remove(key) }
            logger.error {
                "kevent(EV_ADD, fd=$fd, ${interest.name}) for callback failed: " +
                    "${errnoMessage(kevErr)} — readiness callback will not fire"
            }
            return
        }
        wakeup()
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
    internal fun loop() {
        eventLoopThread = pthread_self()
        while (running.value != 0) {
            drainTasks()

            // Non-blocking poll if tasks arrived during drainTasks(),
            // otherwise block until events or wakeup.
            val timeout = if (hasTasksPending()) 0L else KqueueSyscallOps.TIMEOUT_BLOCK
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
                val key = registrationKey(fd, interest)
                // Check callback registrations first (pipeline path),
                // then coroutine registrations (suspend path).
                val cb = withRegLock { callbackRegistrations.remove(key) }
                if (cb != null) {
                    cb()
                } else {
                    val reg = withRegLock { registrations.remove(key) }
                    reg?.continuation?.resume(Unit)
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
     * accumulate faster than kevent() cycles can process them.
     */
    private fun drainTasks() {
        assertInEventLoop("KqueueEventLoop.drainTasks")
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
            pthread_mutex_destroy(regMutex.ptr)
            // taskQueue is MpscQueue (lock-free, no mutex to destroy)
            arena.clear()
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
            register(fd, Interest.WRITE, cont)
            cont.invokeOnCancellation {
                unregister(fd, Interest.WRITE)
                closeFdSafely(fd, logger, "connect cancellation")
            }
        }
    }

    companion object {
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
