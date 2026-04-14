package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
import io_uring.io_uring_buf_ring
import io_uring.io_uring_buf_ring_add
import io_uring.io_uring_buf_ring_advance
import io_uring.io_uring_buf_ring_mask
import io_uring.io_uring_free_buf_ring
import io_uring.io_uring_setup_buf_ring
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.rawValue
import kotlinx.cinterop.value

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
 * @throws IllegalStateException if bufferCount is not a power of 2.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ProvidedBufferRing(
    private val eventLoop: IoUringEventLoop,
    private val logger: Logger,
    val bufferCount: Int = DEFAULT_BUFFER_COUNT,
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    val bgid: Int = 0,
) {
    private val uring get() = eventLoop.ringPtr

    // Contiguous buffer memory: bufferCount × bufferSize bytes.
    // Buffer i starts at basePtr + (i * bufferSize).
    private val basePtr: CPointer<ByteVar> =
        nativeHeap.allocArray<ByteVar>(bufferCount * bufferSize)

    // Kernel-managed buffer ring structure (page-aligned, shared memory with kernel).
    // Null until [initOnEventLoop] has been called.
    private var bufRing: CPointer<io_uring_buf_ring>? = null

    private val mask: Int = io_uring_buf_ring_mask(bufferCount.toUInt()).toInt()

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

        // io_uring_setup_buf_ring allocates page-aligned memory, registers the ring
        // with the kernel, and returns a pointer to the shared io_uring_buf_ring.
        val ring = memScoped {
            val ret = alloc<IntVar>()
            io_uring_setup_buf_ring(
                uring, bufferCount.toUInt(), bgid, 0u, ret.ptr,
            ) ?: error("io_uring_setup_buf_ring failed: ret=${ret.value}")
        }
        bufRing = ring
        // io_uring_buf_ring_init is already called by io_uring_setup_buf_ring
        // (see liburing src/setup.c), so we skip it here.

        // Add all buffers to the ring so the kernel can start selecting them.
        for (i in 0 until bufferCount) {
            io_uring_buf_ring_add(
                ring,
                (basePtr + i * bufferSize)!!,
                bufferSize.toUInt(),
                i.toUShort(),
                mask,
                i,
            )
        }
        io_uring_buf_ring_advance(ring, bufferCount)
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
        val ring = bufRing ?: error("ProvidedBufferRing not yet initialised")
        io_uring_buf_ring_add(
            ring,
            (basePtr + bufId * bufferSize)!!,
            bufferSize.toUInt(),
            bufId.toUShort(),
            mask,
            0,
        )
        io_uring_buf_ring_advance(ring, 1)
    }

    /**
     * Unregisters the buffer ring from the kernel and frees all memory.
     * Called on EventLoop shutdown via [IoUringEventLoop.onExitHook].
     */
    fun close() {
        eventLoop.assertInEventLoop("ProvidedBufferRing.close")
        bufRing?.let { ring ->
            val ret = io_uring_free_buf_ring(uring, ring, bufferCount.toUInt(), bgid)
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
