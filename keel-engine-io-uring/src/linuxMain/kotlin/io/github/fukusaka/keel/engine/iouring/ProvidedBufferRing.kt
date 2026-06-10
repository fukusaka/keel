package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.rawValue

/**
 * Manages a kernel-registered buffer ring for io_uring provided buffers.
 *
 * Pre-allocates [bufferCount] contiguous buffers of [bufferSize] bytes each and
 * registers them with the kernel via `io_uring_setup_buf_ring`. When a multi-shot
 * recv SQE with `IOSQE_BUFFER_SELECT` completes, the kernel selects a buffer from
 * this ring and reports the buffer ID in the CQE flags.
 *
 * **Two-phase lifecycle**: the constructor only allocates user-space state;
 * the kernel `io_uring_setup_buf_ring` call is deferred to [initOnEventLoop]
 * which must run on the owning EventLoop's pthread. Required by
 * `IORING_SETUP_SINGLE_ISSUER`.
 *
 * 1. Construction: allocate buffer memory (user-space only, any thread)
 * 2. [initOnEventLoop]: setup buf ring with kernel (EventLoop pthread)
 * 3. Runtime: [getPointer] / [returnBuffer] (EventLoop pthread)
 * 4. [close]: unregister ring + free memory (EventLoop pthread, via onExitHook)
 *
 * **Buffer exhaustion**: if all buffers are consumed and the ring is empty,
 * the kernel returns `-ENOBUFS` in the CQE and terminates the multi-shot SQE.
 * The caller must re-arm the multi-shot recv **after** recycling buffers, not
 * immediately — re-arming on an empty ring just re-triggers `-ENOBUFS` and
 * busy-loops the EventLoop. A starved transport registers a re-arm via
 * [requestRearmOnAvailable]; [returnBuffer] fires those callbacks once a
 * buffer is back in the ring.
 *
 * @param eventLoop Owning EventLoop. Provides ring pointer and thread-affinity assertion target.
 * @param logger Logger for warn-level diagnostics.
 * @param bufferCount Number of buffers in the ring. Must be a power of 2.
 * @param bufferSize Size of each buffer in bytes.
 * @param bgid Buffer group ID. Each EventLoop should use a unique group ID if
 *             multiple rings are needed (currently one per EventLoop).
 * @param bufferRingOps Provided-buffer-ring syscall seam. Defaults to
 *             [PosixIoUringBufferRingOps]; tests inject a fake to exercise
 *             setup / free failure branches and add / advance bookkeeping.
 * @throws IllegalStateException if bufferCount is not a power of 2.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ProvidedBufferRing(
    private val eventLoop: IoUringEventLoop,
    private val logger: Logger,
    val bufferCount: Int = DEFAULT_BUFFER_COUNT,
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    val bgid: Int = 0,
    private val bufferRingOps: IoUringBufferRingOps = PosixIoUringBufferRingOps,
) {
    private val uring get() = eventLoop.ringPtr

    // Contiguous buffer memory: bufferCount × bufferSize bytes.
    // Buffer i starts at basePtr + (i * bufferSize).
    private val basePtr: CPointer<ByteVar> =
        nativeHeap.allocArray<ByteVar>(bufferCount * bufferSize)

    // Kernel-managed buffer ring handle. Null until [initOnEventLoop] has
    // been called (or after [close]).
    private var bufRing: BufRingHandle? = null

    // Guards [close] against a double `nativeHeap.free(basePtr)`: freeing
    // the same native allocation twice is undefined behaviour.
    private var closed = false

    // Re-arm callbacks for multishot recvs that terminated with `-ENOBUFS`
    // while this shared ring was empty. Drained — each invoked exactly once —
    // the next time a buffer is returned (see [returnBuffer]). One ring is
    // shared by every connection on the owning EventLoop, so a single buffer
    // return may need to re-arm several starved transports. EventLoop-thread
    // only (both [returnBuffer] and [requestRearmOnAvailable] run there), so
    // no synchronisation is needed.
    private val pendingRearm = ArrayList<() -> Unit>()

    // Best-effort count of buffers currently sitting in the kernel ring (i.e.
    // returned and not yet handed to a recv CQE). Used to decide, on `-ENOBUFS`,
    // whether a re-arm can succeed *now*: when a single read delivery is larger
    // than the whole ring (e.g. a ~1 MiB WebSocket frame vs a 512 KiB ring) the
    // kernel fills + reports every buffer and raises `-ENOBUFS` all within one
    // CQE batch, and the application returns those buffers *before* the
    // `-ENOBUFS` CQE is processed — so the [returnBuffer]-triggered re-arm has
    // no later return to fire it and the recv stalls forever. Re-arming
    // immediately when [hasAvailable] is true avoids that stall without
    // reintroducing the busy-loop (we only re-arm when buffers are genuinely
    // back in the ring). EventLoop-thread only.
    private var available = 0

    init {
        check(bufferCount > 0 && (bufferCount and (bufferCount - 1)) == 0) {
            "bufferCount must be a power of 2, got $bufferCount"
        }
    }

    /**
     * Sets up the buf ring with the kernel and populates it with all buffers.
     * Must run on the owning EventLoop pthread.
     */
    fun initOnEventLoop() {
        eventLoop.assertInEventLoop("ProvidedBufferRing.initOnEventLoop")
        if (bufRing != null) return

        // setupBufRing allocates page-aligned memory, registers the ring with
        // the kernel, and returns a handle to the shared io_uring_buf_ring.
        val handle = when (val setup = bufferRingOps.setupBufRing(uring, bufferCount, bgid)) {
            is BufRingSetup.Ok -> setup.handle
            is BufRingSetup.Failed -> error("io_uring_setup_buf_ring failed: ret=${setup.ret}")
        }
        bufRing = handle

        // Add all buffers to the ring so the kernel can start selecting them.
        for (i in 0 until bufferCount) {
            bufferRingOps.addBuffer(handle, (basePtr + i * bufferSize)!!, bufferSize, bid = i, offset = i)
        }
        bufferRingOps.advance(handle, bufferCount)
        available = bufferCount
    }

    /**
     * Returns the data pointer for the buffer identified by [bufId].
     * The pointer is valid until [returnBuffer] is called for the same [bufId].
     */
    fun getPointer(bufId: Int): CPointer<ByteVar> =
        (basePtr + bufId * bufferSize)!!

    /**
     * Returns the buffer identified by [bufId] to the ring so the kernel
     * can reuse it for future multi-shot recv completions.
     *
     * Must be called after the application has finished reading the data.
     */
    fun returnBuffer(bufId: Int) {
        val handle = bufRing ?: error("ProvidedBufferRing not yet initialised")
        bufferRingOps.addBuffer(handle, (basePtr + bufId * bufferSize)!!, bufferSize, bid = bufId, offset = 0)
        bufferRingOps.advance(handle, 1)
        available++
        // A buffer is now available again — re-arm any recvs that gave up on
        // `-ENOBUFS`. Snapshot + clear before invoking: a re-arm that hits
        // `-ENOBUFS` again immediately re-registers, and must land in a fresh
        // list rather than the one being iterated. Allocation only happens on
        // the recovery path (the list is empty in steady state).
        if (pendingRearm.isNotEmpty()) {
            val toRearm = pendingRearm.toTypedArray()
            pendingRearm.clear()
            for (rearm in toRearm) {
                // Per-callback guard: the ring is shared by every connection
                // on this EventLoop, so one transport's throwing re-arm
                // (e.g. an SQ-ring-full error) must not skip the remaining
                // starved transports — they would otherwise never re-arm
                // and starve forever (no later returnBuffer is obligated
                // to come from THEIR buffers).
                try {
                    rearm()
                } catch (t: Throwable) {
                    logger.warn(t) { "deferred recv re-arm threw; remaining re-arms continue" }
                }
            }
        }
    }

    /**
     * Registers a [rearm] callback to run the next time a buffer is returned
     * to this ring (see [returnBuffer]).
     *
     * Called by a transport when its multishot recv terminated with
     * `-ENOBUFS`: re-arming immediately would busy-loop because the kernel
     * keeps re-issuing `-ENOBUFS` for as long as the ring stays empty,
     * burning 100% of the EventLoop. Deferring the re-arm until a buffer is
     * actually available breaks that loop. The callback fires at most once
     * per registration; a still-starved transport re-registers from inside
     * its own re-arm.
     *
     * Must be called on the owning EventLoop pthread.
     */
    fun requestRearmOnAvailable(rearm: () -> Unit) {
        pendingRearm.add(rearm)
    }

    /**
     * Records that the kernel handed one buffer to a multishot recv CQE — the
     * buffer has left the ring until the application returns it via
     * [returnBuffer]. Called once per `res > 0` recv CQE so [hasAvailable]
     * tracks the ring's current occupancy. Clamped at zero so a miscount can
     * never drive [available] negative. Must run on the owning EventLoop pthread.
     */
    fun onConsumed() {
        if (available > 0) available--
    }

    /**
     * True when at least one buffer currently sits in the ring (returned and
     * not yet selected by the kernel). A transport whose multishot recv hit
     * `-ENOBUFS` re-arms immediately when this is true rather than deferring to
     * [requestRearmOnAvailable] — see [available].
     */
    val hasAvailable: Boolean get() = available > 0

    /**
     * Unregisters the buffer ring from the kernel and frees all memory.
     * Called on EventLoop shutdown via [IoUringEventLoop.onExitHook].
     *
     * Idempotent: a second call is a no-op. This guards the
     * `nativeHeap.free(basePtr)` against a double free, which is
     * undefined behaviour.
     */
    fun close() {
        eventLoop.assertInEventLoop("ProvidedBufferRing.close")
        if (closed) return
        closed = true
        bufRing?.let { handle ->
            val ret = bufferRingOps.freeBufRing(uring, handle, bufferCount, bgid)
            if (ret < 0) {
                logger.warn { "io_uring_free_buf_ring() failed: bgid=$bgid ${errnoMessage(-ret)}" }
            }
            bufRing = null
        }
        nativeHeap.free(basePtr.rawValue)
    }

    companion object {
        /** Default number of buffers per ring. Must be a power of 2. */
        const val DEFAULT_BUFFER_COUNT = 64

        /** Default buffer size in bytes. Matches BufferedSuspendSource.BUFFER_SIZE. */
        const val DEFAULT_BUFFER_SIZE = 8192
    }
}
