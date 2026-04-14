package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
import io_uring.io_uring
import io_uring.keel_register_buffers
import io_uring.keel_unregister_buffers
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toLong

/**
 * Manages registered buffers for io_uring SEND_ZC_FIXED operations.
 *
 * Pre-registers pooled buffer addresses with the kernel via
 * `io_uring_register_buffers`. SEND_ZC_FIXED references buffers by
 * index, avoiding per-send page pinning overhead.
 *
 * Created per-EventLoop after pool warmup. The address→index mapping
 * is immutable after construction (no dynamic updates).
 *
 * **Memory**: kernel pins the registered pages (page table entries only,
 * no additional memory allocation). HashMap holds N entries (~64 bytes each)
 * where N = number of pooled buffers per EventLoop (typically 8-16).
 *
 * **Thread safety**: all methods must be called on the owning EventLoop thread.
 *
 * @param ring The io_uring ring to register buffers with.
 * @param buffers Pairs of (native pointer, capacity) from [io.github.fukusaka.keel.buf.SlabAllocator.nativePooledBuffers].
 */
@OptIn(ExperimentalForeignApi::class)
internal class RegisteredBufferTable(
    private val ring: CPointer<io_uring>,
    buffers: List<Pair<CPointer<ByteVar>, Int>>,
    private val logger: Logger,
) {
    // Native pointer rawValue (Long) → registered buffer index.
    private val ptrToIndex = HashMap<Long, Int>(buffers.size * 2)

    /** Whether registration succeeded. */
    val isActive: Boolean

    init {
        if (buffers.isEmpty()) {
            isActive = false
        } else {
            val ret = memScoped {
                val bases = allocArray<COpaquePointerVar>(buffers.size)
                val lens = allocArray<ULongVar>(buffers.size)
                for ((i, pair) in buffers.withIndex()) {
                    val (ptr, cap) = pair
                    bases[i] = ptr
                    lens[i] = cap.convert()
                    ptrToIndex[ptr.rawValue.toLong()] = i
                }
                keel_register_buffers(ring, bases.reinterpret(), lens.reinterpret(), buffers.size)
            }
            isActive = ret >= 0
            if (!isActive) ptrToIndex.clear()
        }
    }

    /**
     * Looks up the registered buffer index for the given native pointer.
     *
     * @return the buffer index (>= 0) for use with SEND_ZC_FIXED,
     *         or -1 if the pointer is not registered.
     */
    fun indexOf(ptr: CPointer<ByteVar>): Int {
        val key = ptr.rawValue.toLong()
        return ptrToIndex[key] ?: -1
    }

    /**
     * Unregisters all buffers from the kernel. Called on EventLoop shutdown.
     */
    fun close() {
        if (isActive) {
            val ret = keel_unregister_buffers(ring)
            if (ret < 0) {
                logger.warn { "io_uring_unregister_buffers() failed: ${errnoMessage(-ret)}" }
            }
        }
    }
}
