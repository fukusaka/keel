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
import kotlinx.coroutines.CancellableContinuation
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
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume

/**
 * Single-threaded epoll event loop for Linux.
 *
 * Drives all I/O for channels created by [EpollEngine]. A dedicated
 * pthread runs [loop], calling `epoll_wait()` to wait for fd readiness
 * events and resuming suspended coroutines when their fds become ready.
 *
 * **Thread model**: The EventLoop thread is created via `pthread_create`
 * rather than Kotlin/Native's `Worker` (deprecated) or coroutine dispatchers
 * (unnecessary overhead for a tight syscall loop). Coroutine continuations
 * are resumed from the EventLoop thread; the coroutine dispatcher handles
 * re-dispatch to the appropriate thread.
 *
 * **Wakeup mechanism**: An `eventfd(2)` is registered with epoll.
 * External threads call [wakeup] to signal the eventfd, causing
 * `epoll_wait()` to return immediately so the EventLoop can process
 * newly registered fds. eventfd is more efficient than pipe(2) on Linux:
 * single fd instead of two, and kernel-optimized for signaling.
 *
 * **Thread safety**: [registrations] is protected by a `pthread_mutex_t`.
 * Kotlin/Native does not support JVM's `synchronized` keyword, and
 * coroutine `Mutex` cannot be used because the EventLoop thread is not
 * in a coroutine context. `pthread_mutex_t` provides the necessary
 * cross-thread synchronization with minimal overhead.
 *
 * ```
 * EventLoop thread:
 *   while (running):
 *     epoll_wait(epFd, timeout=-1)  // block until events
 *     for each ready fd:
 *       if eventfd: consume, continue
 *       lookup registration -> remove -> continuation.resume(Unit)
 *
 * Coroutine thread:
 *   suspendCancellableCoroutine { cont ->
 *     eventLoop.register(fd, READ, cont)
 *   }
 *   // resumed when fd is readable -> retry POSIX read
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollEventLoop {

    /**
     * The epoll file descriptor, created at construction.
     * Exposed for [EpollEngine.bind] to register server fds directly
     * via `epoll_ctl(epFd, ...)`. Channel fds are registered via [register].
     */
    val epFd: Int

    // Arena for long-lived native allocations (mutex, pthread_t).
    // Freed in close().
    private val arena = Arena()
    private val mutex = arena.alloc<pthread_mutex_t>().apply {
        pthread_mutex_init(ptr, null)
    }
    private val registrations = mutableMapOf<Long, Registration>()
    private val wakeupFd: Int
    private val running = AtomicInt(1) // 1 = running, 0 = stopped
    private val threadPtr = arena.alloc<platform.posix.pthread_tVar>()

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

        withLock {
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
        withLock {
            registrations.remove(key)
        }
    }

    /**
     * Wakes up the EventLoop thread by signaling the eventfd.
     * Called after [register] to ensure `epoll_wait()` re-evaluates pending fds.
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

    /**
     * The EventLoop's main loop, running on a dedicated pthread.
     *
     * Calls `epoll_wait()` with timeout=-1 (blocks indefinitely until events).
     * For each ready fd, looks up and removes the registration, then
     * resumes the associated coroutine continuation.
     */
    private fun loop() {
        memScoped {
            val eventList = allocArray<epoll_event>(MAX_EVENTS)
            while (running.value != 0) {
                val n = epoll_wait(epFd, eventList, MAX_EVENTS, -1)
                if (n < 0) {
                    // EINTR: interrupted by signal (e.g. debugger attach).
                    // EAGAIN: spurious wakeup. Both are retriable.
                    val err = errno
                    if (err == EINTR || err == EAGAIN) continue
                    break // Fatal error
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
                    val reg = withLock { registrations.remove(key) }
                    reg?.continuation?.resume(Unit)
                }
            }
        }
    }

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
            val t = threadPtr.ptr[0]
            if (t != null) {
                pthread_join(t, null)
            }
            close(wakeupFd)
            close(epFd)
            pthread_mutex_destroy(mutex.ptr)
            arena.clear()
        }
    }

    /**
     * Encodes fd + interest into a single Long key.
     * fd in lower 32 bits, interest ordinal in upper 32 bits.
     */
    private fun registrationKey(fd: Int, interest: Interest): Long {
        return fd.toLong() or (interest.ordinal.toLong() shl 32)
    }

    /** Runs [block] under the pthread mutex. */
    private inline fun <T> withLock(block: () -> T): T {
        pthread_mutex_lock(mutex.ptr)
        try {
            return block()
        } finally {
            pthread_mutex_unlock(mutex.ptr)
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
