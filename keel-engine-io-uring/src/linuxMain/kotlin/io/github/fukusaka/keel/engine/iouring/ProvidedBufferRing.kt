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
 * The caller must re-arm the multi-shot recv after recycling buffers.
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
    }

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
