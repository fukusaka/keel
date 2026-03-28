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
import io_uring.io_uring_submit_and_wait
import io_uring.keel_eventfd_create
import io_uring.keel_eventfd_write
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
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
 * 2. Submit all pending SQEs + wait for CQEs via `io_uring_submit_and_wait(1)`
 * 3. Drain completed CQEs and resume the associated coroutines
 *
 * **Completion model vs readiness model**: Unlike epoll which notifies when
 * an fd is *ready* (requiring the caller to retry the syscall), io_uring
 * delivers the result of the *completed* operation in the CQE. This eliminates
 * EAGAIN retry loops and allows SQE batching across concurrent operations.
 *
 * **Zero-allocation continuation tracking**: Each SQE stores a slot index
 * (not a pointer) in `user_data`. Continuations are stored in [contSlots],
 * a fixed-size Kotlin array. Free slots are tracked by [freeSlots], an
 * `IntArray`-backed stack. This avoids [StableRef] allocation on every I/O
 * operation — the dominant GC pressure in the single-shot design.
 *
 * **Single-syscall submit + wait**: `io_uring_submit_and_wait(1)` combines
 * SQE submission and CQE waiting into one `io_uring_enter` syscall, halving
 * the per-iteration kernel entry count compared to separate submit + wait calls.
 * This matches the pattern used by tokio-uring and monoio.
 *
 * **SQE prepared during CQE drain**: When [cont.resume][kotlin.coroutines.resume]
 * runs a continuation that calls [submitAndAwait] (fast path), the SQE is
 * prepared inline but not yet submitted. It is submitted on the next
 * `io_uring_submit_and_wait` call — one loop iteration of intentional delay,
 * the same design used by monoio.
 *
 * **Wakeup mechanism**: A permanent eventfd READ SQE with [WAKEUP_TOKEN] as
 * `user_data` keeps at least one SQE in-flight so `io_uring_submit_and_wait`
 * always has something to wait on. External threads call [wakeup] to write to
 * the eventfd, triggering the READ to complete and unblocking the loop.
 * The wakeup SQE is re-submitted after each wakeup CQE.
 *
 * **Limitation — wakeup latency under full ring**: When the SQ ring is full,
 * the wakeup SQE cannot be submitted immediately and is deferred via
 * [wakeupSqePending]. During this window an external [dispatch] call will not
 * immediately wake the EventLoop; the wakeup is delayed until another
 * in-flight SQE completes and the loop retries submission. io_uring does not
 * support dynamic ring resize, so the primary mitigation is choosing a
 * sufficient [ringSize] at construction time.
 *
 * ```
 * user_data encoding:
 *   0              — reserved (unused; safety skip)
 *   WAKEUP_TOKEN   — wakeup eventfd READ SQE
 *   slot + SLOT_BASE — real I/O SQE; slot is an index into contSlots[]
 *
 * EventLoop thread (single loop iteration):
 *   1. drainTasks()                  — run task queue; SQEs are prepared here
 *   2. io_uring_submit_and_wait(1)   — batch-submit SQEs + block for ≥1 CQE
 *   3. drain CQEs (io_uring_peek_cqe):
 *        WAKEUP_TOKEN   → re-submit wakeup SQE, continue
 *        slot+SLOT_BASE → contSlots[slot].resume(res), releaseSlot(slot)
 *        (resumed continuations may prepare new SQEs inline — submitted next iter)
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

    // Pre-allocated drain buffer — reused every loop iteration to avoid
    // per-iteration heap allocation on the hot path.
    private val drainBatch = ArrayList<Runnable>(64)

    // --- Zero-allocation continuation slot pool ---
    //
    // Continuations are stored in a fixed array indexed by slot number.
    // The SQE user_data holds (slot + SLOT_BASE) so the EventLoop can
    // retrieve and resume the continuation without any GC allocation.
    //
    // freeSlots is an IntArray-backed stack: push/pop are plain array
    // reads/writes with no boxing or heap allocation.
    @Suppress("UNCHECKED_CAST")
    private val contSlots = arrayOfNulls<CancellableContinuation<Int>>(ringSize)
        as Array<CancellableContinuation<Int>?>
    private val freeSlots = IntArray(ringSize) { it }
    private var freeSlotsTop = ringSize

    private fun acquireSlot(): Int {
        check(freeSlotsTop > 0) { "Continuation slot pool exhausted (ringSize=$ringSize)" }
        return freeSlots[--freeSlotsTop]
    }

    private fun releaseSlot(slot: Int) {
        freeSlots[freeSlotsTop++] = slot
    }

    private val wakeupFd: Int
    private val running = AtomicInt(1)
    private val threadPtr = arena.alloc<pthread_tVar>()

    // True when submitWakeupSqe() was skipped because the SQ ring was full.
    // Retried at the top of each loop iteration before io_uring_submit_and_wait.
    // EventLoop-thread-only: no synchronisation needed.
    private var wakeupSqePending = false

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
        // will drain tasks before the next io_uring_submit_and_wait().
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
     * A slot index is stored in `sqe.user_data`; the continuation is kept in
     * [contSlots] at that index. No [StableRef] is allocated.
     *
     * **Thread safety**: `io_uring_get_sqe()` and SQE field writes must occur
     * on the EventLoop thread to avoid races with `io_uring_submit_and_wait()`.
     * If called from an external thread, SQE preparation is dispatched to the
     * EventLoop thread via [dispatch], which also calls [wakeup] to break out
     * of any pending `io_uring_submit_and_wait()`. The SQE is submitted on the
     * next `io_uring_submit_and_wait()` call in [loop].
     *
     * @return CQE result: positive = bytes transferred, 0 = EOF/closed,
     *         negative = -errno error code.
     */
    internal suspend fun submitAndAwait(prepare: (CPointer<io_uring_sqe>) -> Unit): Int {
        return suspendCancellableCoroutine { cont ->
            // Hot-path allocation note:
            // [prepare] lambda: allocated by every call site (read/write/accept). Captures local
            // variables (fd, ptr, size), so a new closure object is created each time. Eliminated
            // only by replacing the generic lambda API with typed methods (submitRecv, submitSend).
            //
            // [doSubmit] Runnable: needed only in the slow path (!inEventLoop). Currently allocated
            // unconditionally; the fast path inlines doSubmit.run() but the Runnable is still
            // created. Moving the if/else before Runnable construction would eliminate fast-path
            // allocation at the cost of code duplication.
            val doSubmit = Runnable {
                val sqe = io_uring_get_sqe(ring.ptr)
                    ?: error("io_uring SQ ring full (size=$ringSize)")
                prepare(sqe)
                val slot = acquireSlot()
                contSlots[slot] = cont
                io_uring_sqe_set_data64(sqe, slot.toULong() + SLOT_BASE)
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
     * This keeps the ring occupied so `io_uring_submit_and_wait` always has
     * something to wait on. Called once at startup and again after each wakeup
     * CQE. The SQE is submitted in the next loop iteration's batch.
     *
     * If the SQ ring is full, the submission is deferred: [wakeupSqePending] is
     * set to `true` and the submission is retried at the top of the next loop
     * iteration (before [io_uring_submit_and_wait]). Since the ring being full
     * implies other SQEs are in-flight, [io_uring_submit_and_wait] will return
     * when one of them completes, giving the retry a chance to succeed.
     *
     * **Limitation**: while [wakeupSqePending] is `true`, an external [dispatch]
     * call will not immediately wake the EventLoop. The wakeup is delayed until
     * another in-flight SQE completes and the loop retries this submission.
     * io_uring does not support dynamic ring resize, so the only mitigation is
     * a larger [ringSize] at construction time.
     */
    private fun submitWakeupSqe() {
        val sqe = io_uring_get_sqe(ring.ptr)
        if (sqe == null) {
            wakeupSqePending = true
            return
        }
        wakeupSqePending = false
        io_uring_prep_read(sqe, wakeupFd, wakeupBuf.ptr, 8u, 0u)
        io_uring_sqe_set_data64(sqe, WAKEUP_TOKEN)
    }

    // --- Event loop ---

    private fun loop() {
        eventLoopThread = pthread_self()

        // Prepare the initial wakeup READ SQE.
        // It is submitted on the first io_uring_submit_and_wait() call below,
        // keeping the ring always occupied so the wait never blocks with no
        // in-flight operation.
        submitWakeupSqe()

        memScoped {
            val cqePtrVar = alloc<CPointerVar<io_uring_cqe>>()

            while (running.value != 0) {
                drainTasks()

                // Retry wakeup SQE submission if it was deferred in a prior
                // iteration due to a full SQ ring. Without this, an external
                // dispatch() whose wakeup() write is not caught by any in-flight
                // wakeup SQE would leave the EventLoop blocked indefinitely.
                if (wakeupSqePending) submitWakeupSqe()

                // Submit all pending SQEs and wait for at least 1 CQE in a
                // single io_uring_enter syscall. This halves the per-iteration
                // kernel entry count compared to separate submit + wait calls,
                // following the pattern used by tokio-uring and monoio.
                val ret = io_uring_submit_and_wait(ring.ptr, 1u)
                if (ret < 0) {
                    val err = errno
                    if (err == EINTR) continue
                    logger.error { "io_uring_submit_and_wait() fatal error: errno=$err" }
                    break
                }

                // Drain all available CQEs without blocking.
                // Resumed continuations may prepare new SQEs inline (fast path);
                // those SQEs are submitted on the next io_uring_submit_and_wait().
                while (io_uring_peek_cqe(ring.ptr, cqePtrVar.ptr) == 0) {
                    val cqe = cqePtrVar.value ?: break
                    val userData = io_uring_cqe_get_data64(cqe)
                    val res = cqe.pointed.res
                    io_uring_cqe_seen(ring.ptr, cqe)

                    if (userData == WAKEUP_TOKEN) {
                        // Wakeup fired — re-submit for the next round.
                        // The new SQE is submitted at the next io_uring_submit_and_wait().
                        submitWakeupSqe()
                        continue
                    }

                    if (userData < SLOT_BASE) continue // safety: skip reserved values

                    // Real I/O completion: retrieve continuation from slot, resume it.
                    val slot = (userData - SLOT_BASE).toInt()
                    val cont = contSlots[slot] ?: continue // safety
                    contSlots[slot] = null
                    releaseSlot(slot)
                    cont.resume(res)
                }
            }
        }
    }

    private fun drainTasks() {
        while (true) {
            drainBatch.clear()
            taskQueue.drain(drainBatch)
            if (drainBatch.isEmpty()) return
            // Hot-path allocation note: for-in on ArrayList creates an Iterator object each call.
            // Index-based iteration (for i in 0 until size) avoids this at the cost of readability.
            for (task in drainBatch) task.run()
        }
    }

    companion object {
        /**
         * Default SQE ring size. Must be a power of 2 (io_uring requirement).
         *
         * The wakeup SQE permanently occupies 1 slot, so the effective maximum
         * number of concurrent in-flight I/O operations is `ringSize - 1 = 1023`.
         * Because [flush] awaits the CQE before returning, each connection has at
         * most 1 in-flight SQE at a time, supporting up to ~1023 concurrent connections.
         * Exceeding this limit causes [io_uring_get_sqe] to return null → error.
         *
         * Memory: `ringSize × 12 bytes` for the slot pool (contSlots + freeSlots),
         * i.e. ~12 KB at the default size.
         */
        internal const val DEFAULT_RING_SIZE = 1024

        /**
         * Special user_data value for the permanent wakeup READ SQE.
         * Must not conflict with slot-encoded user_data values (slot + SLOT_BASE).
         */
        internal const val WAKEUP_TOKEN = 1UL

        /**
         * Offset added to slot indices when encoding into user_data.
         * Keeps slot 0 → user_data=2, safely above the reserved range (0, WAKEUP_TOKEN=1).
         */
        internal const val SLOT_BASE = 2UL
    }
}
