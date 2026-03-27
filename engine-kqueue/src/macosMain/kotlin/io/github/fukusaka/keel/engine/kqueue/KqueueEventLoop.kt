package io.github.fukusaka.keel.engine.kqueue

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import io.github.fukusaka.keel.io.MpscQueue
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kqueue.keel_ev_set
import platform.darwin.EV_ADD
import platform.darwin.EVFILT_READ
import platform.darwin.EVFILT_WRITE
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.pipe
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import platform.posix.read
import platform.posix.strerror
import platform.posix.timespec
import platform.posix.write
import kotlinx.cinterop.toKString
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

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
    private val logger: Logger,
) : CoroutineDispatcher() {

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
    private val registrations = mutableMapOf<Long, Registration>()

    // Lock-free MPSC queue replaces pthread_mutex + MutableList for
    // dispatch hot path. CAS (~5-10ns) vs mutex lock/unlock (~50-100ns).
    private val taskQueue = MpscQueue<Runnable>()

    private val wakeupFds = IntArray(2) // [readFd, writeFd]
    // Cached byte arrays to avoid per-wakeup allocation.
    // wakeup() is called once per dispatch/register, so reuse matters.
    private val wakeupWriteBuf = byteArrayOf(1)
    private val wakeupReadBuf = ByteArray(WAKEUP_DRAIN_SIZE)
    private val running = AtomicInt(1) // 1 = running, 0 = stopped
    private val threadPtr = arena.alloc<platform.posix.pthread_tVar>()
    @kotlin.concurrent.Volatile
    private var eventLoopThread: platform.posix.pthread_t? = null

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
        val fd = kqueue()
        check(fd >= 0) { "kqueue() failed" }
        kqFd = fd

        // Create wakeup pipe and register the read end with kqueue
        val result = pipe(wakeupFds.refTo(0))
        check(result == 0) { "pipe() failed" }
        SocketUtils.setNonBlocking(wakeupFds[0])
        SocketUtils.setNonBlocking(wakeupFds[1])

        memScoped {
            val kev = alloc<kevent>()
            keel_ev_set(
                kev.ptr, wakeupFds[0].convert(), EVFILT_READ.convert(),
                EV_ADD.convert(), 0u, 0, null,
            )
            kevent(kqFd, kev.ptr, 1, null, 0, null)
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

    private fun inEventLoop(): Boolean {
        val t = eventLoopThread ?: return false
        return platform.posix.pthread_equal(platform.posix.pthread_self(), t) != 0
    }

    // --- Channel registration ---

    /**
     * Starts the EventLoop thread. Must be called once after construction.
     * The thread runs [loop] until [close] is called.
     */
    fun start() {
        val ref = StableRef.create(this)
        pthread_create(
            threadPtr.ptr, null,
            staticCFunction { arg ->
                val el = arg!!.asStableRef<KqueueEventLoop>().get()
                el.loop()
                arg.asStableRef<KqueueEventLoop>().dispose()
                null
            },
            ref.asCPointer(),
        )
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
        val filter = when (interest) {
            Interest.READ -> EVFILT_READ
            Interest.WRITE -> EVFILT_WRITE
        }
        val key = registrationKey(fd, interest)

        // Add fd to kqueue before registering continuation to avoid
        // missing events that arrive between registration and kevent.
        memScoped {
            val kev = alloc<kevent>()
            keel_ev_set(
                kev.ptr, fd.convert(), filter.convert(),
                EV_ADD.convert(), 0u, 0, null,
            )
            kevent(kqFd, kev.ptr, 1, null, 0, null)
        }

        withRegLock {
            registrations[key] = Registration(fd, interest, cont)
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

    // --- Wakeup ---

    /**
     * Wakes up the EventLoop thread by writing 1 byte to the wakeup pipe.
     * Called after [register] or [dispatch] to ensure `kevent()` re-evaluates
     * pending fds and tasks.
     */
    private fun wakeup() {
        wakeupWriteBuf.usePinned { pinned ->
            write(wakeupFds[1], pinned.addressOf(0), 1u.convert())
        }
    }

    /**
     * Consumes all bytes from the wakeup pipe's read end.
     * Called from the EventLoop thread when the wakeup fd fires.
     */
    private fun consumeWakeup() {
        wakeupReadBuf.usePinned { pinned ->
            while (true) {
                val n = read(wakeupFds[0], pinned.addressOf(0), WAKEUP_DRAIN_SIZE.toULong().convert())
                if (n <= 0) break // EAGAIN or error — all bytes consumed
            }
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
    private fun loop() {
        eventLoopThread = platform.posix.pthread_self()
        memScoped {
            val eventList = allocArray<kevent>(MAX_EVENTS)
            val zeroTimeout = alloc<timespec>().apply {
                tv_sec = 0
                tv_nsec = 0
            }
            while (running.value != 0) {
                drainTasks()

                // Non-blocking poll if tasks arrived during drainTasks(),
                // otherwise block until events or wakeup.
                val timeout = if (hasTasksPending()) zeroTimeout.ptr else null
                val n = kevent(kqFd, null, 0, eventList, MAX_EVENTS, timeout)
                if (n < 0) {
                    // EINTR: interrupted by signal (e.g. debugger attach).
                    // EAGAIN: spurious wakeup. Both are retriable.
                    val err = errno
                    if (err == EINTR || err == EAGAIN) continue
                    // Fatal error — log and terminate the EventLoop thread.
                    // Cannot throw from a pthread; logger is the only output path.
                    val msg = strerror(err)?.toKString() ?: "unknown"
                    logger.error { "kevent() fatal error: $msg (errno=$err)" }
                    break
                }
                for (i in 0 until n) {
                    val ev = eventList[i]
                    val fd = ev.ident.toInt()

                    if (fd == wakeupFds[0]) {
                        consumeWakeup()
                        continue
                    }

                    val interest = when (ev.filter.toInt()) {
                        EVFILT_READ -> Interest.READ
                        EVFILT_WRITE -> Interest.WRITE
                        else -> continue
                    }
                    val key = registrationKey(fd, interest)
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
        val batch = mutableListOf<Runnable>()
        while (true) {
            batch.clear()
            taskQueue.drain(batch)
            if (batch.isEmpty()) return
            for (task in batch) {
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
            close(wakeupFds[0])
            close(wakeupFds[1])
            close(kqFd)
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
