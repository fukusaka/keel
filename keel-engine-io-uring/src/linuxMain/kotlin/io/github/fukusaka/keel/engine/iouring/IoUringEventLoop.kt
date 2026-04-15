package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import io_uring.io_uring_cqe
import io_uring.io_uring_cqe_get_data64
import io_uring.io_uring_cqe_seen
import io_uring.io_uring_get_sqe
import io_uring.io_uring_peek_cqe
import io_uring.io_uring_prep_cancel64
import io_uring.io_uring_prep_read
import io_uring.io_uring_prep_accept
import io_uring.io_uring_prep_writev
import io_uring.iovec
import io_uring.io_uring_queue_exit
import io_uring.io_uring_queue_init
import io_uring.io_uring_sqe
import io_uring.io_uring_sqe_set_data64
import io_uring.io_uring_submit_and_wait
import io_uring.keel_cqe_has_more
import posix_inet.keel_eventfd_create
import io_uring.keel_prep_msg_ring
import io_uring.keel_prep_recv_multishot
import io_uring.keel_prep_send_zc
import io_uring.keel_prep_sendmsg_zc
import io_uring.keel_prep_send_zc_fixed
import io_uring.keel_register_ring_fd
import io_uring.keel_ring_fd
import io_uring.keel_setup_coop_taskrun
import io_uring.keel_setup_defer_taskrun
import io_uring.keel_setup_single_issuer
import io_uring.keel_sqe_set_fixed_file
import io_uring.keel_unregister_ring_fd
import posix_inet.keel_eventfd_write
import kotlinx.cinterop.Arena
import kotlinx.cinterop.COpaquePointer
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
import io.github.fukusaka.keel.buf.MpscQueue
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.close
import platform.posix.errno
import platform.posix.pthread_create
import platform.posix.pthread_equal
import platform.posix.pthread_join
import platform.posix.pthread_self
import platform.posix.pthread_t
import platform.posix.pthread_tVar
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicLong
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
 *   CANCEL_TOKEN   — IORING_OP_ASYNC_CANCEL SQE; CQE is discarded
 *   slot + SLOT_BASE — real I/O SQE; slot is an index into contSlots[]
 *
 * EventLoop thread (single loop iteration):
 *   1. drainTasks()                  — run task queue; SQEs are prepared here
 *   2. io_uring_submit_and_wait(1)   — batch-submit SQEs + block for ≥1 CQE
 *   3. drain CQEs (io_uring_peek_cqe):
 *        WAKEUP_TOKEN   → re-submit wakeup SQE, continue
 *        CANCEL_TOKEN   → discard (cancel SQE completed; original CQE follows)
 *        slot+SLOT_BASE → multishot: callbackSlots[slot](res, flags),
 *                         release slot when IORING_CQE_F_MORE drops
 *                       → single-shot: contSlots[slot].resume(res), releaseSlot(slot)
 *        (resumed continuations may prepare new SQEs inline — submitted next iter)
 * ```
 *
 * @param logger Logger for error reporting.
 * @param ringSize Number of SQE entries in the submission ring. Must be a power of 2.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringEventLoop(
    internal val logger: Logger,
    private val capabilities: IoUringCapabilities = IoUringCapabilities(),
    private val ringSize: Int = DEFAULT_RING_SIZE,
) : CoroutineDispatcher() {

    // Arena for long-lived native allocations.
    // Freed in close() after io_uring_queue_exit.
    private val arena = Arena()

    private val ring = arena.alloc<io_uring>()

    /** Exposes the io_uring ring pointer for [ProvidedBufferRing] registration. */
    internal val ringPtr get() = ring.ptr

    /**
     * Exposes the kernel ring file descriptor so peer EventLoops can target
     * this ring via `IORING_OP_MSG_RING` when [IoUringCapabilities.msgRingWakeup]
     * is enabled. Populated on the EventLoop pthread inside [loop] after the
     * ring is created; reads before that return -1 (unused because MSG_RING
     * submissions only happen after [start] and group init barrier complete).
     */
    @kotlin.concurrent.Volatile
    internal var ringFd: Int = -1
        private set

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
    //
    // For multishot SQEs (one SQE → multiple CQEs), multishotCallbacks[]
    // stores a callback instead of a continuation. The slot is held until
    // IORING_CQE_F_MORE drops, indicating the kernel will produce no more
    // CQEs for that SQE.
    private val contSlots = arrayOfNulls<CancellableContinuation<Int>>(ringSize)
    // Callback slots shared by submitMultishot (multishot SQEs) and
    // submitCallback (single-shot fire-and-forget SQEs). The slot is held
    // until IORING_CQE_F_MORE drops — for single-shot SQEs this is always
    // the first (and only) CQE, so the slot is released immediately.
    private val callbackSlots = arrayOfNulls<(Int, UInt) -> Unit>(ringSize)
    // SEND_ZC: stores the first CQE result while waiting for the second (notification) CQE.
    // SEND_ZC_UNUSED marks the slot as not in SEND_ZC mode.
    private val sendZcPendingResult = IntArray(ringSize) { SEND_ZC_UNUSED }
    // SEND_ZC fire-and-forget: callback invoked with send result after both CQEs arrive.
    // Used by submitSendZcCallback as alternative to contSlots for non-suspend callers.
    private val sendZcCallbacks = arrayOfNulls<(Int) -> Unit>(ringSize)
    private val freeSlots = IntArray(ringSize) { it }
    private var freeSlotsTop = ringSize

    private fun acquireSlot(): Int {
        check(freeSlotsTop > 0) { "Continuation slot pool exhausted (ringSize=$ringSize)" }
        return freeSlots[--freeSlotsTop]
    }

    private fun releaseSlot(slot: Int) {
        freeSlots[freeSlotsTop++] = slot
    }

    /**
     * Completes a SEND_ZC slot by resuming the continuation or invoking the callback.
     *
     * Checks [contSlots] first (suspend path), then [sendZcCallbacks] (fire-and-forget).
     */
    private fun completeZcSlot(slot: Int, result: Int) {
        val cont = contSlots[slot]
        if (cont != null) {
            contSlots[slot] = null
            releaseSlot(slot)
            cont.resume(result)
        } else {
            val cb = sendZcCallbacks[slot]
            sendZcCallbacks[slot] = null
            releaseSlot(slot)
            cb?.invoke(result)
        }
    }

    private val wakeupFd: Int
    private val running = AtomicInt(1)
    private val threadPtr = arena.alloc<pthread_tVar>()

    // True when submitWakeupSqe() was skipped because the SQ ring was full.
    // Retried at the top of each loop iteration before io_uring_submit_and_wait.
    // EventLoop-thread-only: no synchronisation needed.
    private var wakeupSqePending = false

    @kotlin.concurrent.Volatile
    private var eventLoopThread: pthread_t? = null

    init {
        // Only allocate user-space state here. `io_uring_queue_init` is deferred
        // to [initRing] which runs on the EventLoop pthread in [loop] — this is
        // a precondition for `IORING_SETUP_SINGLE_ISSUER`, which records the
        // first `io_uring_register_*` or `io_uring_enter` caller as the
        // submitter task and rejects submissions from any other pthread.
        wakeupFd = keel_eventfd_create()
        check(wakeupFd >= 0) { "eventfd() failed" }
    }

    /**
     * Initialises the io_uring ring. Must be called on the EventLoop pthread —
     * invoked as the first action of [loop].
     */
    private fun initRing() {
        var flags = 0u
        if (capabilities.coopTaskrun) flags = flags or keel_setup_coop_taskrun()
        if (capabilities.singleIssuer) flags = flags or keel_setup_single_issuer()
        // DEFER_TASKRUN requires SINGLE_ISSUER per kernel; rely on detect()
        // keeping them consistent and do not enforce at this layer (user override
        // is intentional).
        if (capabilities.deferTaskrun) flags = flags or keel_setup_defer_taskrun()
        val ret = io_uring_queue_init(ringSize.toUInt(), ring.ptr, flags)
        check(ret == 0) { "io_uring_queue_init() failed: $ret (flags=0x${flags.toString(16)})" }
    }

    // --- CoroutineDispatcher ---

    /**
     * Dispatches a coroutine block to run on this EventLoop thread.
     *
     * The block is queued and, if the caller is on a different thread, the
     * target EventLoop is woken up. Two wakeup paths exist:
     *
     *  1. **MSG_RING** — when [IoUringCapabilities.msgRingWakeup] is enabled and
     *     the caller is running on some (other) keel EventLoop pthread, the
     *     source EL submits an `IORING_OP_MSG_RING` SQE on its own ring
     *     targeting this EL's ring fd. No syscall is issued by the source
     *     (the SQE is flushed in the source EL's next `io_uring_submit_and_wait`).
     *     The kernel synthesises a CQE with [MSG_RING_WAKEUP_TOKEN] on the
     *     target ring, which returns the target EL from its own
     *     `io_uring_submit_and_wait` and triggers [drainTasks] on the next
     *     iteration.
     *
     *  2. **eventfd_write** (fallback) — used when the caller is an external
     *     thread (no source ring available), when MSG_RING support is off,
     *     or when the source SQ ring is full. A 1-syscall write to this EL's
     *     wakeup eventfd; the permanent READ SQE delivers a CQE with
     *     [WAKEUP_TOKEN] and the loop re-arms it.
     */
    override fun dispatch(context: CoroutineContext, block: Runnable) {
        taskQueue.offer(block)
        // Skip wakeup when already on the EventLoop thread — the loop
        // will drain tasks before the next io_uring_submit_and_wait().
        if (inEventLoop()) return

        if (capabilities.msgRingWakeup) {
            val source = currentEventLoop
            // MSG_RING is only valid from another EL pthread (source owns a
            // ring). Same-EL dispatch already returned above; external threads
            // leave `currentEventLoop` null and fall through to eventfd.
            if (source != null && source !== this && source.submitMsgRingTo(this)) {
                return
            }
        }
        wakeup()
    }

    /**
     * Submits an `IORING_OP_MSG_RING` SQE on this EventLoop's ring targeting
     * [target]. Called by [dispatch] from the source EL pthread when MSG_RING
     * wakeup is enabled and the peer EL is different from the source.
     *
     * Returns `true` if the SQE was enqueued on the source ring; `false` if
     * the source SQ is full and the caller should fall back to eventfd.
     * The source-side completion CQE carries [MSG_RING_SEND_TOKEN] and is
     * discarded by the CQE drain loop. The target-side CQE carries
     * [MSG_RING_WAKEUP_TOKEN] and is also discarded — the task itself was
     * already pushed to the target's [taskQueue] by [dispatch].
     *
     * @return true if MSG_RING was successfully queued on the source ring.
     */
    private fun submitMsgRingTo(target: IoUringEventLoop): Boolean {
        assertInEventLoop("submitMsgRingTo")
        val sqe = io_uring_get_sqe(ring.ptr) ?: return false
        val targetFd = target.ringFd
        if (targetFd < 0) return false // target ring not yet initialised
        keel_prep_msg_ring(sqe, targetFd, 0u, MSG_RING_WAKEUP_TOKEN, 0u)
        io_uring_sqe_set_data64(sqe, MSG_RING_SEND_TOKEN)
        return true
    }

    /**
     * Returns `true` if the current pthread is this EventLoop's thread.
     * Returns `false` before [start] has been called (ring init phase).
     */
    internal fun inEventLoop(): Boolean {
        val t = eventLoopThread ?: return false
        return pthread_equal(pthread_self(), t) != 0
    }

    /**
     * Throws [IllegalStateException] if called from a thread other than this
     * EventLoop's pthread. Used by per-EventLoop resources ([FixedFileRegistry],
     * [ProvidedBufferRing], [RegisteredBufferTable]) to assert thread-affinity
     * preconditions: their internal state is not synchronised and
     * `IORING_SETUP_SINGLE_ISSUER` additionally requires `io_uring_register_*`
     * to run on the submitter task.
     *
     * Returns without checking if the EventLoop has not yet started — the
     * register-class `initOnEventLoop` path runs during startup orchestration.
     */
    internal fun assertInEventLoop(operation: String) {
        val t = eventLoopThread ?: return
        check(pthread_equal(pthread_self(), t) != 0) {
            "$operation must run on the EventLoop thread"
        }
    }

    /**
     * Hook invoked on the EventLoop pthread as the last action of [loop],
     * after the main drain loop exits but before the ring is destroyed.
     * Used by [IoUringEventLoopGroup] to wire per-EventLoop register-class
     * teardown (unregister files / buffers / free buf ring) on the submitter
     * task while the kernel ring is still alive.
     */
    internal var onExitHook: (() -> Unit)? = null

    // --- Lifecycle ---

    /** Starts the EventLoop thread. Must be called once after construction. */
    fun start() {
        val ref = StableRef.create(this)
        val ret = pthread_create(
            threadPtr.ptr, null,
            staticCFunction { arg ->
                val el = arg!!.asStableRef<IoUringEventLoop>().get()
                el.loop()
                arg.asStableRef<IoUringEventLoop>().dispose()
                null
            },
            ref.asCPointer(),
        )
        if (ret != 0) {
            // pthread_create returned an error — the EventLoop thread was never
            // started. Dispose the StableRef now since the thread won't run the
            // dispose call. Fail-fast because the engine is unusable.
            ref.dispose()
            error("pthread_create() failed: ${errnoMessage(ret)}")
        }
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
                val joinRet = pthread_join(t, null)
                if (joinRet != 0) {
                    logger.warn { "pthread_join() failed: ${errnoMessage(joinRet)}" }
                }
            }
            io_uring_queue_exit(ring.ptr)
            val closeRet = close(wakeupFd)
            if (closeRet != 0) {
                logger.warn { "close(wakeupFd) failed: ${errnoMessage(errno)}" }
            }
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
     * **Cancellation**: if the coroutine is cancelled while waiting for the CQE,
     * `IORING_OP_ASYNC_CANCEL` is submitted targeting the original SQE's user_data.
     * The cancel CQE arrives with [CANCEL_TOKEN] and is discarded. The original
     * SQE's CQE arrives with -ECANCELED; resuming the already-cancelled continuation
     * with that value is a safe no-op. The slot is always released.
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
     * @throws IllegalStateException if the SQ ring is full.
     */
    internal suspend fun submitAndAwait(prepare: (CPointer<io_uring_sqe>) -> Unit): Int {
        return suspendCancellableCoroutine { cont ->
            if (inEventLoop()) {
                // Fast path: prepare SQE synchronously on the EventLoop thread.
                // SQE user_data (slot index) is known immediately, so invokeOnCancellation
                // captures it directly without AtomicLong.
                //
                // Remaining hot-path allocations: [prepare] lambda (always, captures fd/ptr/size)
                // and the invokeOnCancellation lambda (always, captures userData). Eliminated only
                // by replacing the generic lambda API with typed methods (submitAccept, submitCallback).
                val sqe = io_uring_get_sqe(ring.ptr)
                    ?: error("io_uring SQ ring full (size=$ringSize)")
                prepare(sqe)
                val slot = acquireSlot()
                contSlots[slot] = cont
                val userData = slot.toULong() + SLOT_BASE
                io_uring_sqe_set_data64(sqe, userData)
                cont.invokeOnCancellation {
                    // Dispatch ASYNC_CANCEL to the EventLoop thread: io_uring_get_sqe()
                    // must be called from the EventLoop thread only.
                    // If the ring is full, the cancel SQE is silently dropped; the original
                    // SQE will complete on its own and the slot will be released normally.
                    dispatch(EmptyCoroutineContext, Runnable {
                        val cancelSqe = io_uring_get_sqe(ring.ptr) ?: return@Runnable
                        io_uring_prep_cancel64(cancelSqe, userData, 0)
                        io_uring_sqe_set_data64(cancelSqe, CANCEL_TOKEN)
                    })
                }
            } else {
                // Slow path: dispatch SQE preparation to the EventLoop thread.
                // AtomicLong bridges the race between the dispatch Runnable (EventLoop
                // thread, writes userData after SQE submission) and invokeOnCancellation
                // (arbitrary thread, reads userData to decide whether to cancel).
                val submittedUserData = AtomicLong(0L)
                cont.invokeOnCancellation {
                    val ud = submittedUserData.value
                    if (ud == 0L) return@invokeOnCancellation
                    dispatch(EmptyCoroutineContext, Runnable {
                        val cancelSqe = io_uring_get_sqe(ring.ptr) ?: return@Runnable
                        io_uring_prep_cancel64(cancelSqe, ud.toULong(), 0)
                        io_uring_sqe_set_data64(cancelSqe, CANCEL_TOKEN)
                    })
                }
                dispatch(EmptyCoroutineContext, Runnable {
                    // If cancelled before this Runnable ran, skip SQE submission:
                    // the caller will receive CancellationException without any in-flight SQE.
                    if (!cont.isActive) return@Runnable
                    val sqe = io_uring_get_sqe(ring.ptr)
                        ?: error("io_uring SQ ring full (size=$ringSize)")
                    prepare(sqe)
                    val slot = acquireSlot()
                    contSlots[slot] = cont
                    val userData = slot.toULong() + SLOT_BASE
                    io_uring_sqe_set_data64(sqe, userData)
                    submittedUserData.value = userData.toLong()
                })
            }
        }
    }

    // --- Typed SQE submission (hot-path, zero lambda allocation) ---

    /**
     * Fire-and-forget `IORING_OP_SEND_ZC` with 2-CQE callback handling.
     *
     * Submits a SEND_ZC SQE and invokes [onComplete] with the send result
     * after BOTH CQEs arrive (send result + buffer release notification).
     * The caller's buffer is safe to release inside [onComplete].
     *
     * Uses [sendZcCallbacks] instead of [contSlots] for the callback slot.
     * CQE dispatch checks `sendZcCallbacks[slot]` when `contSlots[slot]` is null.
     *
     * Must be called on the EventLoop thread only.
     *
     * @throws IllegalStateException if the SQ ring is full.
     */
    internal fun submitSendZcCallback(
        fd: Int, buf: COpaquePointer, len: ULong, flags: Int,
        fixedFile: Boolean = false,
        onComplete: (bytesOrError: Int) -> Unit,
    ) {
        val sqe = io_uring_get_sqe(ring.ptr)
            ?: error("io_uring SQ ring full (size=$ringSize)")
        keel_prep_send_zc(sqe, fd, buf, len, flags, 0u)
        if (fixedFile) keel_sqe_set_fixed_file(sqe)
        val slot = acquireSlot()
        sendZcCallbacks[slot] = onComplete
        sendZcPendingResult[slot] = SEND_ZC_UNUSED + 1
        val userData = slot.toULong() + SLOT_BASE
        io_uring_sqe_set_data64(sqe, userData)
    }

    /**
     * Fire-and-forget `IORING_OP_SEND_ZC` with registered (fixed) buffer.
     *
     * Like [submitSendZcCallback] but uses a pre-registered buffer index
     * to avoid per-send page pinning. The buffer must have been registered
     * via [RegisteredBufferTable].
     */
    internal fun submitSendZcFixedCallback(
        fd: Int, buf: COpaquePointer, len: ULong, flags: Int,
        bufIndex: Int,
        fixedFile: Boolean = false,
        onComplete: (bytesOrError: Int) -> Unit,
    ) {
        val sqe = io_uring_get_sqe(ring.ptr)
            ?: error("io_uring SQ ring full (size=$ringSize)")
        keel_prep_send_zc_fixed(sqe, fd, buf, len, flags, 0u, bufIndex.toUInt())
        if (fixedFile) keel_sqe_set_fixed_file(sqe)
        val slot = acquireSlot()
        sendZcCallbacks[slot] = onComplete
        sendZcPendingResult[slot] = SEND_ZC_UNUSED + 1
        val userData = slot.toULong() + SLOT_BASE
        io_uring_sqe_set_data64(sqe, userData)
    }

    /**
     * Fire-and-forget `IORING_OP_SENDMSG_ZC` with two-CQE callback.
     *
     * Submits a SENDMSG_ZC SQE (gather write + zero-copy) and invokes
     * [onComplete] with the total bytes sent after both CQEs arrive.
     * The msghdr and its iovec array must remain valid until completion.
     *
     * Must be called on the EventLoop thread only.
     */
    internal fun submitSendmsgZcCallback(
        fd: Int,
        msghdr: kotlinx.cinterop.COpaquePointer,
        flags: UInt,
        fixedFile: Boolean = false,
        onComplete: (bytesOrError: Int) -> Unit,
    ) {
        val sqe = io_uring_get_sqe(ring.ptr)
            ?: error("io_uring SQ ring full (size=$ringSize)")
        keel_prep_sendmsg_zc(sqe, fd, msghdr, flags)
        if (fixedFile) keel_sqe_set_fixed_file(sqe)
        val slot = acquireSlot()
        sendZcCallbacks[slot] = onComplete
        sendZcPendingResult[slot] = SEND_ZC_UNUSED + 1
        val userData = slot.toULong() + SLOT_BASE
        io_uring_sqe_set_data64(sqe, userData)
    }

    /**
     * Fire-and-forget `IORING_OP_WRITEV` with single-CQE callback.
     *
     * Submits a WRITEV SQE and invokes [onComplete] with the total bytes
     * written (or negative errno) when the single CQE arrives.
     *
     * Must be called on the EventLoop thread only.
     */
    internal fun submitWritevCallback(
        fd: Int, iovecs: CPointer<iovec>, count: UInt,
        fixedFile: Boolean = false,
        onComplete: (bytesOrError: Int) -> Unit,
    ) {
        submitCallback(
            prepare = { sqe ->
                io_uring_prep_writev(sqe, fd, iovecs, count, 0u)
                if (fixedFile) keel_sqe_set_fixed_file(sqe)
            },
            onCqe = { res, _ -> onComplete(res) },
        )
    }

    /**
     * Submits `IORING_OP_ACCEPT` (single-shot) and suspends until the CQE arrives.
     *
     * Used as fallback when multishot accept is not available (kernel < 5.19).
     * On the EventLoop thread (hot path), prepares the SQE inline without
     * allocating a `prepare` lambda.
     *
     * @throws IllegalStateException if the SQ ring is full.
     */
    internal suspend fun submitAccept(serverFd: Int): Int {
        if (!inEventLoop()) return submitAndAwait { sqe -> io_uring_prep_accept(sqe, serverFd, null, null, 0) }
        return suspendCancellableCoroutine { cont ->
            val sqe = io_uring_get_sqe(ring.ptr)
                ?: error("io_uring SQ ring full (size=$ringSize)")
            io_uring_prep_accept(sqe, serverFd, null, null, 0)
            submitSqe(sqe, cont)
        }
    }

    /**
     * Common fast-path SQE submission: assigns a slot, stores the continuation,
     * and sets user_data. Called after the SQE is already prepared by
     * [submitAccept].
     *
     * **No invokeOnCancellation**: The typed API methods are used on the hot
     * path (read/write/flush) where cancellation is handled by `Channel.close()`
     * → `close(fd)` → kernel cancels in-flight SQEs → CQE with -ECANCELED
     * → slot released via normal CQE drain. This avoids allocating a cancellation
     * lambda on every I/O operation. The generic [submitAndAwait] still registers
     * `IORING_OP_ASYNC_CANCEL` for non-hot-path operations (connect, accept).
     *
     * Must be called on the EventLoop thread only.
     */
    private fun submitSqe(sqe: CPointer<io_uring_sqe>, cont: CancellableContinuation<Int>) {
        val slot = acquireSlot()
        contSlots[slot] = cont
        val userData = slot.toULong() + SLOT_BASE
        io_uring_sqe_set_data64(sqe, userData)
    }

    // --- Multishot SQE submission ---

    /**
     * Submits a multishot SQE and routes all resulting CQEs to [onCqe].
     *
     * Unlike [submitAndAwait] which resumes a single continuation on one CQE,
     * a multishot SQE produces multiple CQEs (e.g., `IORING_OP_ACCEPT` with
     * `IORING_ACCEPT_MULTISHOT`). The [onCqe] callback receives each CQE's
     * `(res, flags)` pair. The slot is held until `IORING_CQE_F_MORE` drops,
     * indicating the kernel will produce no more CQEs for this SQE.
     *
     * Must be called on the EventLoop thread only.
     *
     * @param prepare Fills in the SQE via `io_uring_prep_*` functions.
     * @param onCqe   Callback invoked on the EventLoop thread for each CQE.
     *                `res` is the CQE result; `flags` contains CQE flags
     *                (check `keel_cqe_has_more` for continuation).
     * @return The slot index, needed for [cancelMultishot].
     * @throws IllegalStateException if the SQ ring is full.
     */
    internal fun submitMultishot(
        prepare: (CPointer<io_uring_sqe>) -> Unit,
        onCqe: (res: Int, flags: UInt) -> Unit,
    ): Int {
        val sqe = io_uring_get_sqe(ring.ptr)
            ?: error("io_uring SQ ring full (size=$ringSize)")
        prepare(sqe)
        val slot = acquireSlot()
        callbackSlots[slot] = onCqe
        val userData = slot.toULong() + SLOT_BASE
        io_uring_sqe_set_data64(sqe, userData)
        return slot
    }

    /**
     * Submits a single-shot SQE and routes the CQE to [onCqe] (fire-and-forget).
     *
     * Unlike [submitMultishot], this is for SQEs that produce exactly one CQE
     * (e.g., SEND, WRITEV). The slot is released after the first callback.
     * Does not return a slot index (cancellation not supported — use
     * `close(fd)` for implicit kernel cancellation).
     *
     * Must be called on the EventLoop thread only.
     *
     * @param prepare Fills in the SQE via `io_uring_prep_*` functions.
     * @param onCqe   Callback invoked with `(res, flags)` when the CQE arrives.
     * @throws IllegalStateException if the SQ ring is full.
     */
    internal fun submitCallback(
        prepare: (CPointer<io_uring_sqe>) -> Unit,
        onCqe: (res: Int, flags: UInt) -> Unit,
    ) {
        val sqe = io_uring_get_sqe(ring.ptr)
            ?: error("io_uring SQ ring full (size=$ringSize)")
        prepare(sqe)
        val slot = acquireSlot()
        callbackSlots[slot] = onCqe
        val userData = slot.toULong() + SLOT_BASE
        io_uring_sqe_set_data64(sqe, userData)
    }

    /**
     * Cancels a multishot SQE.
     *
     * Submits `IORING_OP_ASYNC_CANCEL` targeting the multishot SQE's user_data
     * and replaces the callback with a no-op. The slot is NOT released here;
     * it is released by the CQE drain loop when the final CQE arrives with
     * `IORING_CQE_F_MORE == 0` (the kernel's `-ECANCELED` response). This
     * prevents a slot reuse race where a new operation could be assigned the
     * same slot before the kernel delivers the cancellation CQE.
     *
     * Must be called on the EventLoop thread only.
     *
     * @param slot The slot index returned by [submitMultishot].
     */
    internal fun cancelMultishot(slot: Int) {
        // Replace with no-op; the drain loop releases the slot on F_MORE=0.
        callbackSlots[slot] = { _, _ -> }
        val cancelSqe = io_uring_get_sqe(ring.ptr) ?: return
        val userData = slot.toULong() + SLOT_BASE
        io_uring_prep_cancel64(cancelSqe, userData, 0)
        io_uring_sqe_set_data64(cancelSqe, CANCEL_TOKEN)
    }

    /**
     * Submits a multishot recv SQE with provided buffer selection.
     *
     * Combines `IORING_RECV_MULTISHOT` and `IOSQE_BUFFER_SELECT` in a single
     * SQE: the kernel delivers one CQE per incoming data segment, selecting a
     * buffer from the provided buffer ring identified by [bgid]. This eliminates
     * per-read SQE resubmission and buffer allocation overhead.
     *
     * Must be called on the EventLoop thread only.
     *
     * @param fd   The connected socket file descriptor.
     * @param bgid Buffer group ID for the provided buffer ring.
     * @param onCqe Callback invoked on the EventLoop thread for each CQE.
     * @return The slot index, needed for [cancelMultishot].
     * @throws IllegalStateException if the SQ ring is full.
     */
    internal fun submitMultishotRecv(
        fd: Int,
        bgid: Int,
        fixedFile: Boolean = false,
        onCqe: (res: Int, flags: UInt) -> Unit,
    ): Int {
        val sqe = io_uring_get_sqe(ring.ptr)
            ?: error("io_uring SQ ring full (size=$ringSize)")
        keel_prep_recv_multishot(sqe, fd, bgid.toUShort())
        if (fixedFile) keel_sqe_set_fixed_file(sqe)
        val slot = acquireSlot()
        callbackSlots[slot] = onCqe
        val userData = slot.toULong() + SLOT_BASE
        io_uring_sqe_set_data64(sqe, userData)
        return slot
    }

    // --- Wakeup ---

    private fun wakeup() {
        val ret = keel_eventfd_write(wakeupFd)
        if (ret < 0) {
            // EAGAIN means the eventfd counter would overflow (it has reached
            // UINT64_MAX - 1). The pending writes are sufficient to wake the
            // EventLoop; logging at debug level keeps the path quiet.
            // Other failures (EBADF, EINVAL) indicate a programming error and
            // are surfaced at warn level.
            val err = errno
            if (err == EAGAIN) {
                logger.debug { "eventfd_write skipped: counter at maximum (already woken)" }
            } else {
                logger.warn { "eventfd_write() failed: ${errnoMessage(err)}" }
            }
        }
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
        // Publish this EL as the current one on this pthread so peer
        // EventLoops can route MSG_RING wakeups via [dispatch] → [submitMsgRingTo].
        // External threads never run loop(), so [currentEventLoop] remains null
        // for them and dispatch() falls back to eventfd.
        currentEventLoop = this

        // Initialise the kernel ring on this pthread so SINGLE_ISSUER claims
        // this thread as the submitter task.
        initRing()
        // Expose the ring fd now that the kernel ring is created. Peer
        // EventLoops read this via MSG_RING dispatch; the group init barrier
        // in IoUringEventLoopGroup.start() ensures every loop has published
        // its fd before any dispatch can be observed.
        ringFd = keel_ring_fd(ring.ptr)
        // Self-register the ring fd (opt-in, Linux 5.18+). Subsequent
        // io_uring_submit_and_wait calls use the registered index via
        // IORING_ENTER_REGISTERED_RING, skipping the kernel fd-table lookup.
        // Register failures are warn-logged and the EventLoop continues with
        // the slow path — the optimisation is best-effort.
        if (capabilities.registerRingFd) {
            val ret = keel_register_ring_fd(ring.ptr)
            if (ret < 0) {
                logger.warn { "io_uring_register_ring_fd() failed: ${errnoMessage(-ret)}" }
            }
        }

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
                    val cqeFlags = cqe.pointed.flags
                    io_uring_cqe_seen(ring.ptr, cqe)

                    if (userData == WAKEUP_TOKEN) {
                        // Wakeup fired — re-submit for the next round.
                        // The new SQE is submitted at the next io_uring_submit_and_wait().
                        submitWakeupSqe()
                        continue
                    }

                    // MSG_RING wakeup (target side): a peer EL woke us.
                    // The task itself was enqueued on taskQueue by the peer's
                    // dispatch() call before the MSG_RING SQE was submitted,
                    // so drainTasks() at the top of the next loop iteration
                    // will pick it up. Nothing else to do for this CQE.
                    if (userData == MSG_RING_WAKEUP_TOKEN) continue

                    // MSG_RING completion (source side): discard. Source CQEs
                    // arrive on the EL that submitted the MSG_RING SQE and
                    // report the send result; res < 0 means the target ring
                    // was closed or unreachable, which is benign at shutdown.
                    if (userData == MSG_RING_SEND_TOKEN) {
                        if (res < 0) {
                            logger.debug { "MSG_RING send failed: ${errnoMessage(-res)}" }
                        }
                        continue
                    }

                    // ASYNC_CANCEL completion: discard. The original SQE's CQE (-ECANCELED)
                    // arrives separately and is handled via the normal slot path below.
                    if (userData == CANCEL_TOKEN) continue

                    if (userData < SLOT_BASE) continue // safety: skip reserved values

                    val slot = (userData - SLOT_BASE).toInt()

                    // Callback path (multishot + single-shot fire-and-forget):
                    // invoke callback, release slot when F_MORE drops.
                    // Single-shot SQEs never set F_MORE, so slot is released on first CQE.
                    val msCb = callbackSlots[slot]
                    if (msCb != null) {
                        msCb(res, cqeFlags)
                        if (keel_cqe_has_more(cqeFlags) == 0) {
                            callbackSlots[slot] = null
                            releaseSlot(slot)
                        }
                        continue
                    }

                    // SEND_ZC path: two CQEs per operation.
                    // First CQE: store send result, wait for second.
                    // Second CQE: resume continuation or invoke callback with stored result.
                    val zcPending = sendZcPendingResult[slot]
                    if (zcPending != SEND_ZC_UNUSED) {
                        if (zcPending == SEND_ZC_UNUSED + 1) {
                            // First CQE: store send result
                            sendZcPendingResult[slot] = res
                            if (keel_cqe_has_more(cqeFlags) == 0) {
                                // No notification CQE — complete immediately
                                sendZcPendingResult[slot] = SEND_ZC_UNUSED
                                completeZcSlot(slot, res)
                            }
                            // F_MORE set → wait for second CQE
                        } else {
                            // Second CQE: buffer release notification
                            val sendResult = zcPending
                            sendZcPendingResult[slot] = SEND_ZC_UNUSED
                            completeZcSlot(slot, sendResult)
                        }
                        continue
                    }

                    // Single-shot path: retrieve continuation from slot, resume it.
                    val cont = contSlots[slot] ?: continue // safety
                    contSlots[slot] = null
                    releaseSlot(slot)
                    cont.resume(res)
                }
            }
        }

        // Run the exit hook (register-class teardown) on this pthread while
        // the kernel ring is still alive. [close] will tear down the ring
        // after this function returns.
        onExitHook?.invoke()
        // Paired unregister for registerRingFd. io_uring_queue_exit would
        // clean this up internally, but the explicit call keeps register /
        // unregister symmetric and surfaces any kernel-side breakage as a
        // warn-level log.
        if (capabilities.registerRingFd) {
            val ret = keel_unregister_ring_fd(ring.ptr)
            if (ret < 0) {
                logger.warn { "io_uring_unregister_ring_fd() failed: ${errnoMessage(-ret)}" }
            }
        }
        // Invalidate the ring fd before the ring is destroyed. Peer
        // EventLoops dispatching to this EL after shutdown fall back to the
        // eventfd path (which will no-op on the closed fd at worst).
        ringFd = -1
        // Clear the pthread's current-EL pointer so subsequent code running
        // on this pthread (if any) does not misattribute itself to this EL.
        currentEventLoop = null
    }

    private fun drainTasks() {
        while (true) {
            drainBatch.clear()
            taskQueue.drain(drainBatch)
            if (drainBatch.isEmpty()) return
            // Index-based iteration avoids Iterator allocation on every drain cycle.
            for (i in 0 until drainBatch.size) drainBatch[i].run()
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
         * Special user_data value for `IORING_OP_ASYNC_CANCEL` SQEs.
         * The CQE for a cancel SQE is discarded; the original SQE's CQE
         * (with -ECANCELED) arrives separately via the normal slot path.
         */
        internal const val CANCEL_TOKEN = ULong.MAX_VALUE

        /**
         * Source-side user_data for `IORING_OP_MSG_RING` SQEs. The CQE on the
         * source ring reports the send result; keel discards the CQE unless
         * `res < 0` (debug-logged as a benign shutdown race).
         *
         * Value: `ULong.MAX_VALUE - 1` (must not collide with [CANCEL_TOKEN]
         * = `ULong.MAX_VALUE`, [WAKEUP_TOKEN] = 1, or slot-encoded values).
         */
        internal const val MSG_RING_SEND_TOKEN = 0xFFFFFFFFFFFFFFFEUL

        /**
         * Target-side user_data for `IORING_OP_MSG_RING` SQEs (delivered via
         * the `data` parameter in [keel_prep_msg_ring]). The CQE appears on
         * the target ring; keel uses it purely to return the target loop
         * from `io_uring_submit_and_wait` — the actual task was already
         * enqueued on the target's [taskQueue] before the SQE was submitted.
         *
         * Value: `ULong.MAX_VALUE - 2`.
         */
        internal const val MSG_RING_WAKEUP_TOKEN = 0xFFFFFFFFFFFFFFFDUL

        /**
         * Offset added to slot indices when encoding into user_data.
         * Keeps slot 0 → user_data=2, safely above the reserved range (0, WAKEUP_TOKEN=1).
         */
        internal const val SLOT_BASE = 2UL

        /**
         * Marker value for [sendZcPendingResult] indicating the slot is not
         * in SEND_ZC mode. Chosen to be distinguishable from any valid CQE
         * result (which is a byte count or negative errno).
         */
        private const val SEND_ZC_UNUSED = Int.MIN_VALUE
    }
}

/**
 * pthread-local pointer to the [IoUringEventLoop] currently running on this
 * pthread, or `null` if the current thread is not an io_uring EventLoop
 * pthread (external callers, `Dispatchers.Default`, etc.).
 *
 * Set on entry to [IoUringEventLoop.loop] and cleared on exit. Read by
 * [IoUringEventLoop.dispatch] to decide whether a cross-EL wakeup can use
 * `IORING_OP_MSG_RING` (source needs to own a ring) or must fall back to
 * the eventfd path (external thread, no ring available).
 *
 * Kotlin/Native `@ThreadLocal` marks this top-level `var` as having a
 * per-pthread storage slot; writes on one pthread are invisible to others.
 */
@kotlin.native.concurrent.ThreadLocal
internal var currentEventLoop: IoUringEventLoop? = null
