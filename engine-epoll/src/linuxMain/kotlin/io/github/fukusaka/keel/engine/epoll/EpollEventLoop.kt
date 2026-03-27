package io.github.fukusaka.keel.engine.epoll

import epoll.keel_eventfd_create
import epoll.keel_eventfd_read
import epoll.keel_eventfd_write
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import io.github.fukusaka.keel.io.MpscQueue
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.linux.EPOLL_CTL_ADD
import platform.linux.epoll_create1
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.strerror
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlinx.cinterop.toKString
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
    private val logger: Logger,
) : CoroutineDispatcher() {

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
    private val registrations = mutableMapOf<Long, Registration>()

    // Lock-free MPSC queue replaces pthread_mutex + MutableList for
    // dispatch hot path. CAS (~5-10ns) vs mutex lock/unlock (~50-100ns).
    private val taskQueue = MpscQueue<Runnable>()

    private val wakeupFd: Int
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
        val fd = epoll_create1(0)
        check(fd >= 0) { "epoll_create1() failed" }
        epFd = fd

        // Create eventfd for wakeup and register with epoll.
        // eventfd is more efficient than pipe on Linux: single fd,
        // kernel-optimized for event signaling.
        wakeupFd = keel_eventfd_create()
        check(wakeupFd >= 0) { "eventfd() failed" }

        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = EPOLLIN.toUInt()
            ev.data.fd = wakeupFd
            epoll_ctl(epFd, EPOLL_CTL_ADD, wakeupFd, ev.ptr)
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
                val el = arg!!.asStableRef<EpollEventLoop>().get()
                el.loop()
                arg.asStableRef<EpollEventLoop>().dispose()
                null
            },
            ref.asCPointer(),
        )
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

        // Add fd to epoll before registering continuation to avoid
        // missing events that arrive between registration and epoll_wait.
        memScoped {
            val ev = alloc<epoll_event>()
            ev.events = events.toUInt()
            ev.data.fd = fd
            epoll_ctl(epFd, EPOLL_CTL_ADD, fd, ev.ptr)
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
     * Wakes up the EventLoop thread by signaling the eventfd.
     * Called after [register] or [dispatch] to ensure `epoll_wait()`
     * re-evaluates pending fds and tasks.
     */
    private fun wakeup() {
        keel_eventfd_write(wakeupFd)
    }

    /**
     * Consumes the eventfd counter to reset it.
     * Called from the EventLoop thread when the eventfd fires.
     */
    private fun consumeWakeup() {
        keel_eventfd_read(wakeupFd)
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
    private fun loop() {
        eventLoopThread = platform.posix.pthread_self()
        memScoped {
            val eventList = allocArray<epoll_event>(MAX_EVENTS)
            while (running.value != 0) {
                drainTasks()

                // Non-blocking poll if tasks arrived during drainTasks(),
                // otherwise block until events or wakeup.
                // epoll_wait timeout: 0 = immediate, -1 = indefinite block.
                val timeout = if (hasTasksPending()) 0 else -1
                val n = epoll_wait(epFd, eventList, MAX_EVENTS, timeout)
                if (n < 0) {
                    // EINTR: interrupted by signal (e.g. debugger attach).
                    // EAGAIN: spurious wakeup. Both are retriable.
                    val err = errno
                    if (err == EINTR || err == EAGAIN) continue
                    // Fatal error — log and terminate the EventLoop thread.
                    // Cannot throw from a pthread; logger is the only output path.
                    logger.error { "epoll_wait() fatal error: ${strerror(err)?.toKString()} (errno=$err)" }
                    break
                }
                for (i in 0 until n) {
                    val ev = eventList[i]
                    val fd = ev.data.fd

                    if (fd == wakeupFd) {
                        consumeWakeup()
                        continue
                    }

                    // Determine interest from epoll event flags.
                    // EPOLLIN maps to READ, EPOLLOUT maps to WRITE.
                    val interest = if (ev.events.toInt() and EPOLLIN != 0) {
                        Interest.READ
                    } else {
                        Interest.WRITE
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
     * accumulate faster than epoll_wait() cycles can process them.
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
            close(wakeupFd)
            close(epFd)
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
         * Maximum events per epoll_wait() call. 64 balances memory usage
         * (64 * sizeof(epoll_event) = ~768 bytes on x86_64) against
         * reducing the number of epoll_wait() syscalls under high fd counts.
         */
        private const val MAX_EVENTS = 64
    }
}
