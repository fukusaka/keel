package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
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
 * **Two-phase lifecycle**: the constructor only builds the user-space
 * pointer→index map; the kernel `io_uring_register_buffers` call is
 * deferred to [initOnEventLoop] which must run on the owning EventLoop's
 * pthread. Required by `IORING_SETUP_SINGLE_ISSUER`.
 *
 * **Memory**: kernel pins the registered pages (page table entries only,
 * no additional memory allocation). HashMap holds N entries (~64 bytes each)
 * where N = number of pooled buffers per EventLoop (typically 8-16).
 *
 * **Thread safety**: all methods except the constructor must run on the
 * owning EventLoop pthread.
 *
 * @param eventLoop Owning EventLoop. Provides ring pointer and thread-affinity assertion target.
 * @param buffers Pairs of (native pointer, capacity) from [io.github.fukusaka.keel.buf.SlabAllocator.nativePooledBuffers].
 * @param logger Logger for warn-level diagnostics.
 */
@OptIn(ExperimentalForeignApi::class)
internal class RegisteredBufferTable(
    private val eventLoop: IoUringEventLoop,
    private val buffers: List<Pair<CPointer<ByteVar>, Int>>,
    private val logger: Logger,
) {
    private val ring get() = eventLoop.ringPtr

    // Native pointer rawValue (Long) → registered buffer index.
    private val ptrToIndex = HashMap<Long, Int>(buffers.size * 2).also { map ->
        for ((i, pair) in buffers.withIndex()) {
            val (ptr, _) = pair
            map[ptr.rawValue.toLong()] = i
        }
    }

    /** Whether kernel registration succeeded. Set by [initOnEventLoop]. */
    var isActive: Boolean = false
        private set

    /**
     * Registers the buffers with the kernel on the owning EventLoop pthread.
     * Silently no-ops if [buffers] is empty (no pooled buffers to register).
     */
    fun initOnEventLoop() {
        eventLoop.assertInEventLoop("RegisteredBufferTable.initOnEventLoop")
        if (isActive || buffers.isEmpty()) return
        val ret = memScoped {
            val bases = allocArray<COpaquePointerVar>(buffers.size)
            val lens = allocArray<ULongVar>(buffers.size)
            for ((i, pair) in buffers.withIndex()) {
                val (ptr, cap) = pair
                bases[i] = ptr
                lens[i] = cap.convert()
            }
            keel_register_buffers(ring, bases.reinterpret(), lens.reinterpret(), buffers.size)
        }
        isActive = ret >= 0
        if (!isActive) ptrToIndex.clear()
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
     * Unregisters all buffers from the kernel. Called on EventLoop shutdown
     * via [IoUringEventLoop.onExitHook] so the call runs on the submitter task.
     */
    fun close() {
        eventLoop.assertInEventLoop("RegisteredBufferTable.close")
        if (isActive) {
            val ret = keel_unregister_buffers(ring)
            if (ret < 0) {
                logger.warn { "io_uring_unregister_buffers() failed: ${errnoMessage(-ret)}" }
            }
            isActive = false
        }
    }
}
