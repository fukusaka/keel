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
import kotlinx.coroutines.CancellableContinuation
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
import platform.posix.write
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume

/**
 * Single-threaded kqueue event loop for macOS.
 *
 * Drives all I/O for channels created by [KqueueEngine]. A dedicated
 * pthread runs [loop], calling `kevent()` to wait for fd readiness
 * events and resuming suspended coroutines when their fds become ready.
 *
 * **Thread model**: The EventLoop thread is created via `pthread_create`
 * rather than Kotlin/Native's `Worker` (deprecated) or coroutine dispatchers
 * (unnecessary overhead for a tight syscall loop). Coroutine continuations
 * are resumed from the EventLoop thread; the coroutine dispatcher handles
 * re-dispatch to the appropriate thread.
 *
 * **Wakeup mechanism**: A `pipe(2)` fd pair is registered with kqueue.
 * External threads call [wakeup] to write 1 byte to the pipe, causing
 * `kevent()` to return immediately so the EventLoop can process newly
 * registered fds.
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
 *     kevent(kqFd, timeout=null)  // block until events
 *     for each ready fd:
 *       if wakeup pipe: consume byte, continue
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
internal class KqueueEventLoop {

    /**
     * The kqueue file descriptor, created at construction.
     * Exposed for [KqueueEngine.bind] to register server fds directly
     * via `kevent(kqFd, ...)`. Channel fds are registered via [register].
     */
    val kqFd: Int

    // Arena for long-lived native allocations (mutex).
    // Freed in close().
    private val arena = Arena()
    private val mutex = arena.alloc<pthread_mutex_t>().apply {
        pthread_mutex_init(ptr, null)
    }
    private val registrations = mutableMapOf<Long, Registration>()
    private val wakeupFds = IntArray(2) // [readFd, writeFd]
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
     * Wakes up the EventLoop thread by writing 1 byte to the wakeup pipe.
     * Called after [register] to ensure `kevent()` re-evaluates pending fds.
     */
    private fun wakeup() {
        byteArrayOf(1).usePinned { pinned ->
            write(wakeupFds[1], pinned.addressOf(0), 1u.convert())
        }
    }

    /**
     * Consumes all bytes from the wakeup pipe's read end.
     * Called from the EventLoop thread when the wakeup fd fires.
     */
    private fun consumeWakeup() {
        ByteArray(64).usePinned { pinned ->
            while (true) {
                val n = read(wakeupFds[0], pinned.addressOf(0), 64u.convert())
                if (n <= 0) break // EAGAIN or error — all bytes consumed
            }
        }
    }

    /**
     * The EventLoop's main loop, running on a dedicated pthread.
     *
     * Calls `kevent()` with no timeout (blocks indefinitely until events).
     * For each ready fd, looks up and removes the registration, then
     * resumes the associated coroutine continuation.
     */
    private fun loop() {
        memScoped {
            val eventList = allocArray<kevent>(MAX_EVENTS)
            while (running.value != 0) {
                val n = kevent(kqFd, null, 0, eventList, MAX_EVENTS, null)
                if (n < 0) {
                    // EINTR: interrupted by signal (e.g. debugger attach).
                    // EAGAIN: spurious wakeup. Both are retriable.
                    val err = errno
                    if (err == EINTR || err == EAGAIN) continue
                    break // Fatal error
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
         * Maximum events per kevent() call. 64 balances memory usage
         * (64 * sizeof(kevent) = ~2.5 KiB on arm64) against reducing
         * the number of kevent() syscalls under high fd counts.
         * Netty uses 4096; 64 is conservative for initial implementation.
         */
        private const val MAX_EVENTS = 64
    }
}
