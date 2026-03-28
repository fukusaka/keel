package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import io_uring.io_uring_cqe
import io_uring.io_uring_cqe_get_data64
import io_uring.io_uring_cqe_seen
import io_uring.io_uring_get_sqe
import io_uring.io_uring_peek_cqe
import io_uring.io_uring_prep_read
import io_uring.io_uring_queue_exit
import io_uring.io_uring_queue_init
import io_uring.io_uring_sqe
import io_uring.io_uring_sqe_set_data64
import io_uring.io_uring_submit
import io_uring.io_uring_wait_cqe
import io_uring.keel_eventfd_create
import io_uring.keel_eventfd_write
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toLong
import kotlinx.cinterop.value
import io.github.fukusaka.keel.io.MpscQueue
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.pthread_create
import platform.posix.pthread_equal
import platform.posix.pthread_join
import platform.posix.pthread_self
import platform.posix.pthread_tVar
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume

/**
 * Single-threaded io_uring event loop for Linux, also serving as a [CoroutineDispatcher].
 *
 * Drives all I/O for channels created by [IoUringEngine]. A dedicated
 * pthread runs [loop], interleaving three tasks:
 * 1. Execute queued coroutine continuations ([taskQueue])
 * 2. Submit all pending SQEs via a single `io_uring_submit()` call (batching)
 * 3. Drain completed CQEs and resume the associated coroutines
 *
 * **Completion model vs readiness model**: Unlike epoll which notifies when
 * an fd is *ready* (requiring the caller to retry the syscall), io_uring
 * delivers the result of the *completed* operation in the CQE. This eliminates
 * EAGAIN retry loops and allows SQE batching across concurrent operations.
 *
 * **No registration map**: epoll requires a map from fd to continuation.
 * io_uring stores a 64-bit `user_data` in each SQE, echoed back in the CQE.
 * We store a [StableRef] to the [CancellableContinuation] directly in
 * `user_data`, avoiding any map lookup on the hot path.
 *
 * **SQE batching**: All SQEs prepared during [drainTasks] are submitted in
 * one `io_uring_submit()` call before waiting for CQEs. This amortises the
 * `io_uring_enter` syscall overhead across concurrent I/O operations.
 *
 * **Wakeup mechanism**: A permanent eventfd READ SQE with [WAKEUP_TOKEN] as
 * `user_data` keeps the ring occupied so `io_uring_wait_cqe` never blocks
 * indefinitely. External threads call [wakeup] to write to the eventfd,
 * triggering the READ to complete and breaking out of `io_uring_wait_cqe`.
 * The wakeup SQE is re-submitted after each wakeup CQE.
 *
 * ```
 * EventLoop thread (single loop iteration):
 *   1. drainTasks()        — run coroutine continuations; SQEs are prepared here
 *   2. io_uring_submit()   — batch-submit all pending SQEs to the kernel
 *   3. io_uring_wait_cqe() — block until at least 1 CQE arrives
 *   4. drain CQEs:
 *        WAKEUP_TOKEN → re-submit wakeup SQE, continue
 *        else         → StableRef.get() → cont.resume(cqe.res) → dispose ref
 * ```
 *
 * @param logger Logger for error reporting.
 * @param ringSize Number of SQE entries in the submission ring. Must be a power of 2.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringEventLoop(
    private val logger: Logger,
    private val ringSize: Int = DEFAULT_RING_SIZE,
) : CoroutineDispatcher() {

    // Arena for long-lived native allocations.
    // Freed in close() after io_uring_queue_exit.
    private val arena = Arena()

    private val ring = arena.alloc<io_uring>()

    // 8-byte buffer for eventfd reads (uint64_t). Arena-allocated so it
    // remains valid for the lifetime of the permanent wakeup READ SQE.
    private val wakeupBuf = arena.alloc<ULongVar>()

    // Lock-free MPSC queue for cross-thread task dispatch.
    // CAS-based enqueue (~5-10ns) vs mutex lock/unlock (~50-100ns).
    private val taskQueue = MpscQueue<Runnable>()

    private val wakeupFd: Int
    private val running = AtomicInt(1)
    private val threadPtr = arena.alloc<pthread_tVar>()

    @kotlin.concurrent.Volatile
    private var eventLoopThread: platform.posix.pthread_t? = null

    init {
        val ret = io_uring_queue_init(ringSize.toUInt(), ring.ptr, 0u)
        check(ret == 0) { "io_uring_queue_init() failed: $ret" }

        wakeupFd = keel_eventfd_create()
        check(wakeupFd >= 0) { "eventfd() failed" }
    }

    // --- CoroutineDispatcher ---

    /**
     * Dispatches a coroutine block to run on this EventLoop thread.
     *
     * The block is queued and, if called from an external thread, the
     * EventLoop is woken up via eventfd to process the new task.
     */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskQueue.offer(block)
        // Skip wakeup when already on the EventLoop thread — the loop
        // will drain tasks before the next io_uring_submit().
        if (!inEventLoop()) {
            wakeup()
        }
    }

    private fun inEventLoop(): Boolean {
        val t = eventLoopThread ?: return false
        return pthread_equal(pthread_self(), t) != 0
    }

    // --- Lifecycle ---

    /** Starts the EventLoop thread. Must be called once after construction. */
    fun start() {
        val ref = StableRef.create(this)
        pthread_create(
            threadPtr.ptr, null,
            staticCFunction { arg ->
                val el = arg!!.asStableRef<IoUringEventLoop>().get()
                el.loop()
                arg.asStableRef<IoUringEventLoop>().dispose()
                null
            },
            ref.asCPointer(),
        )
    }

    /**
     * Stops the EventLoop and releases all resources.
     *
     * Signals the loop to stop via [running] + [wakeup], joins the thread,
     * calls `io_uring_queue_exit` to unmap the ring, and closes the eventfd.
     */
    fun close() {
        if (running.compareAndSet(1, 0)) {
            wakeup()
            val t = threadPtr.ptr[0]
            if (t != null) {
                pthread_join(t, null)
            }
            io_uring_queue_exit(ring.ptr)
            close(wakeupFd)
            arena.clear()
        }
    }

    // --- SQE submission ---

    /**
     * Prepares an SQE and suspends the coroutine until the CQE arrives.
     *
     * The [prepare] lambda fills in the SQE via `io_uring_prep_*` functions.
     * A [StableRef] to the continuation is stored in `sqe.user_data` so the
     * EventLoop can resume it on CQE arrival.
     *
     * **Thread safety**: `io_uring_get_sqe()` and SQE field writes must occur
     * on the EventLoop thread to avoid races with `io_uring_submit()`. If
     * [submitAndAwait] is called from an external thread (e.g., the test's
     * `runBlocking` context), SQE preparation is dispatched to the EventLoop
     * thread via [dispatch], which also calls [wakeup] to break out of any
     * pending `io_uring_wait_cqe()`. The SQE is submitted on the next
     * `io_uring_submit()` call in [loop].
     *
     * @return CQE result: positive = bytes transferred, 0 = EOF/closed,
     *         negative = -errno error code.
     */
    internal suspend fun submitAndAwait(prepare: (CPointer<io_uring_sqe>) -> Unit): Int {
        return suspendCancellableCoroutine { cont ->
            val doSubmit = Runnable {
                val sqe = io_uring_get_sqe(ring.ptr)
                    ?: error("io_uring SQ ring full (size=$ringSize)")
                prepare(sqe)
                val ref = StableRef.create(cont)
                io_uring_sqe_set_data64(sqe, ref.asCPointer().toLong().toULong())
            }
            if (inEventLoop()) {
                doSubmit.run()
            } else {
                dispatch(EmptyCoroutineContext, doSubmit)
            }
        }
    }

    // --- Wakeup ---

    private fun wakeup() {
        keel_eventfd_write(wakeupFd)
    }

    /**
     * Prepares a READ SQE on [wakeupFd] with [WAKEUP_TOKEN] as user_data.
     *
     * This keeps the ring occupied so `io_uring_wait_cqe` always has something
     * to wait on. Called once at startup and again after each wakeup CQE.
     * The SQE is submitted in the next loop iteration's batch.
     */
    private fun submitWakeupSqe() {
        val sqe = io_uring_get_sqe(ring.ptr) ?: return // ring full; retry on next submit
        io_uring_prep_read(sqe, wakeupFd, wakeupBuf.ptr, 8u, 0u)
        io_uring_sqe_set_data64(sqe, WAKEUP_TOKEN)
    }

    // --- Event loop ---

    private fun loop() {
        eventLoopThread = pthread_self()

        // Submit the initial wakeup SQE before entering the loop.
        // This ensures io_uring_wait_cqe always has something to wait on.
        submitWakeupSqe()
        io_uring_submit(ring.ptr)

        memScoped {
            val cqePtrVar = alloc<CPointerVar<io_uring_cqe>>()

            while (running.value != 0) {
                drainTasks()

                // Batch-submit all SQEs prepared during drainTasks().
                // One io_uring_enter syscall covers all concurrent I/O.
                io_uring_submit(ring.ptr)

                // Block until at least one CQE is available.
                val ret = io_uring_wait_cqe(ring.ptr, cqePtrVar.ptr)
                if (ret < 0) {
                    val err = errno
                    if (err == EINTR) continue
                    logger.error { "io_uring_wait_cqe() fatal error: errno=$err" }
                    break
                }

                // Drain all available CQEs without blocking.
                while (io_uring_peek_cqe(ring.ptr, cqePtrVar.ptr) == 0) {
                    val cqe = cqePtrVar.value ?: break
                    val userData = io_uring_cqe_get_data64(cqe)
                    val res = cqe.pointed.res
                    io_uring_cqe_seen(ring.ptr, cqe)

                    if (userData == WAKEUP_TOKEN) {
                        // Wakeup fired — re-submit for the next round.
                        // The new SQE will be submitted at the next io_uring_submit().
                        submitWakeupSqe()
                        continue
                    }

                    // Real I/O completion: restore StableRef, resume continuation.
                    if (userData == 0UL) continue // safety: skip zero user_data
                    val ref = userData.toLong().toCPointer<CPointed>()!!
                        .asStableRef<CancellableContinuation<Int>>()
                    val cont = ref.get()
                    ref.dispose()
                    cont.resume(res)
                }
            }
        }
    }

    private fun drainTasks() {
        val batch = mutableListOf<Runnable>()
        while (true) {
            batch.clear()
            taskQueue.drain(batch)
            if (batch.isEmpty()) return
            for (task in batch) task.run()
        }
    }

    companion object {
        /**
         * Default SQE ring size. 1024 supports ~500 concurrent connections with
         * 2 in-flight operations each (read + write), plus the wakeup SQE.
         * Must be a power of 2 (io_uring requirement).
         */
        internal const val DEFAULT_RING_SIZE = 1024

        /**
         * Special user_data value for the permanent wakeup READ SQE.
         * A valid StableRef pointer is never 0 or 1, so 1 is a safe sentinel.
         */
        internal const val WAKEUP_TOKEN = 1UL
    }
}
