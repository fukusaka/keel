package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
import io_uring.io_uring
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
 * **Lifecycle**:
 * 1. Construction: allocate buffer memory + register ring with kernel
 * 2. Kernel consumes buffers: CQE reports `buf_id` → [getPointer] returns data pointer
 * 3. Application recycles: [returnBuffer] adds the buffer back to the ring
 * 4. Close: unregister ring + free memory
 *
 * **Thread safety**: all operations must occur on a single EventLoop thread.
 * No synchronisation is required.
 *
 * **Buffer exhaustion**: if all buffers are consumed and the ring is empty,
 * the kernel returns `-ENOBUFS` in the CQE and terminates the multi-shot SQE.
 * The caller must re-arm the multi-shot recv after recycling buffers.
 *
 * @param uring Pointer to the io_uring instance (must remain valid for the lifetime of this ring).
 * @param bufferCount Number of buffers in the ring. Must be a power of 2.
 * @param bufferSize Size of each buffer in bytes.
 * @param bgid Buffer group ID. Each EventLoop should use a unique group ID if
 *             multiple rings are needed (currently one per EventLoop).
 * @throws IllegalStateException if bufferCount is not a power of 2 or kernel registration fails.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ProvidedBufferRing(
    private val uring: CPointer<io_uring>,
    private val logger: Logger,
    val bufferCount: Int = DEFAULT_BUFFER_COUNT,
    val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    val bgid: Int = 0,
) {

    // Contiguous buffer memory: bufferCount × bufferSize bytes.
    // Buffer i starts at basePtr + (i * bufferSize).
    private val basePtr: CPointer<ByteVar> =
        nativeHeap.allocArray<ByteVar>(bufferCount * bufferSize)

    // Kernel-managed buffer ring structure (page-aligned, shared memory with kernel).
    private val bufRing: CPointer<io_uring_buf_ring>

    private val mask: Int

    init {
        check(bufferCount > 0 && (bufferCount and (bufferCount - 1)) == 0) {
            "bufferCount must be a power of 2, got $bufferCount"
        }

        // io_uring_setup_buf_ring allocates page-aligned memory, registers the ring
        // with the kernel, and returns a pointer to the shared io_uring_buf_ring.
        bufRing = memScoped {
            val ret = alloc<IntVar>()
            io_uring_setup_buf_ring(
                uring, bufferCount.toUInt(), bgid, 0u, ret.ptr,
            ) ?: error("io_uring_setup_buf_ring failed: ret=${ret.value}")
        }

        mask = io_uring_buf_ring_mask(bufferCount.toUInt()).toInt()
        // io_uring_buf_ring_init is already called by io_uring_setup_buf_ring
        // (see liburing src/setup.c), so we skip it here.

        // Add all buffers to the ring so the kernel can start selecting them.
        for (i in 0 until bufferCount) {
            io_uring_buf_ring_add(
                bufRing,
                (basePtr + i * bufferSize)!!,
                bufferSize.toUInt(),
                i.toUShort(),
                mask,
                i,
            )
        }
        io_uring_buf_ring_advance(bufRing, bufferCount)
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
        io_uring_buf_ring_add(
            bufRing,
            (basePtr + bufId * bufferSize)!!,
            bufferSize.toUInt(),
            bufId.toUShort(),
            mask,
            0,
        )
        io_uring_buf_ring_advance(bufRing, 1)
    }

    /**
     * Unregisters the buffer ring from the kernel and frees all memory.
     */
    fun close() {
        val ret = io_uring_free_buf_ring(uring, bufRing, bufferCount.toUInt(), bgid)
        if (ret < 0) {
            logger.warn { "io_uring_free_buf_ring() failed: bgid=$bgid ${errnoMessage(-ret)}" }
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
