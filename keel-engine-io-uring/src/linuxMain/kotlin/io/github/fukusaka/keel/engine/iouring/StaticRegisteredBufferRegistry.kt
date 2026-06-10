package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.collections.LongObjectMap
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.native.posix.errnoMessage
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toLong

/**
 * [IoUringFixedBufferRegistry] for [RegisteredBufferStrategy.STATIC]: a
 * one-shot kernel registration of N buffer slots that stays valid for the
 * lifetime of the owning EventLoop. The registered set is the per-EL
 * allocator's pre-warmed pool, enumerated once at startup; subsequent
 * allocations either hit a pooled buffer (in the registered set and
 * therefore eligible for `SEND_ZC_FIXED`) or miss to a fresh
 * `nativeHeap.allocArray` (outside the set and therefore routed to
 * regular `SEND_ZC` by [indexOf] returning `-1`).
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
 * Renamed from `RegisteredBufferTable` in the PR introducing
 * [RegisteredBufferStrategy]; behaviour is unchanged.
 *
 * @param eventLoop Owning EventLoop. Provides ring pointer and thread-affinity assertion target.
 * @param buffers Pairs of (native pointer, capacity) from [io.github.fukusaka.keel.buf.enumerateNativePooledBuffers].
 * @param logger Logger for warn-level diagnostics.
 * @param bufferOps Registered-buffer syscall seam. Defaults to
 *                  [PosixIoUringRegisteredBufferOps]; tests inject a fake to
 *                  exercise the kernel registration failure branch.
 */
@OptIn(ExperimentalForeignApi::class)
internal class StaticRegisteredBufferRegistry(
    private val eventLoop: IoUringEventLoop,
    private val buffers: List<Pair<CPointer<ByteVar>, Int>>,
    private val logger: Logger,
    private val bufferOps: IoUringRegisteredBufferOps = PosixIoUringRegisteredBufferOps,
) : IoUringFixedBufferRegistry {
    private val ring get() = eventLoop.ringPtr

    // Native pointer rawValue (Long) → registered buffer index.
    // Uses LongObjectMap (Fibonacci top-bit hash) over HashMap<Long, Int>
    // because page-aligned pooled-buffer pointers are exactly the case the
    // top-bit extraction is designed to handle (low N bits are zero — naïve
    // identity / xor-shift hash collides on slot 0).
    private val ptrToIndex = LongObjectMap<Int>(buffers.size * 2).also { map ->
        for ((i, pair) in buffers.withIndex()) {
            val (ptr, _) = pair
            map[ptr.rawValue.toLong()] = i
        }
    }

    /** Whether kernel registration succeeded. Set by [initOnEventLoop]. */
    override var isActive: Boolean = false
        private set

    /**
     * Registers the buffers with the kernel on the owning EventLoop pthread.
     * Silently no-ops if [buffers] is empty (no pooled buffers to register).
     */
    override fun initOnEventLoop() {
        eventLoop.assertInEventLoop("StaticRegisteredBufferRegistry.initOnEventLoop")
        if (isActive || buffers.isEmpty()) return
        val ret = bufferOps.registerBuffers(ring, buffers)
        isActive = ret >= 0
        if (!isActive) ptrToIndex.clear()
    }

    /**
     * Looks up the registered buffer index for the given native pointer.
     *
     * @return the buffer index (>= 0) for use with SEND_ZC_FIXED,
     *         or -1 if the pointer is not registered.
     */
    override fun indexOf(ptr: CPointer<ByteVar>): Int = ptrToIndex[ptr.rawValue.toLong()] ?: -1

    /**
     * Unregisters all buffers from the kernel. Called on EventLoop shutdown
     * via [IoUringEventLoop.onExitHook] so the call runs on the submitter task.
     */
    override fun close() {
        eventLoop.assertInEventLoop("StaticRegisteredBufferRegistry.close")
        if (isActive) {
            val ret = bufferOps.unregisterBuffers(ring)
            if (ret < 0) {
                logger.warn { "io_uring_unregister_buffers() failed: ${errnoMessage(-ret)}" }
            }
            isActive = false
        }
    }
}
