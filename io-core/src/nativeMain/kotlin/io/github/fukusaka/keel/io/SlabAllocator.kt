package io.github.fukusaka.keel.io

/**
 * Pool-based [BufferAllocator] for Native targets.
 *
 * Maintains a freelist of pre-sized [NativeBuf] instances. When a buffer
 * is released, it is returned to the pool instead of being freed. This
 * eliminates per-connection `nativeHeap.allocArray` / `nativeHeap.free`
 * overhead.
 *
 * **Thread safety**: not thread-safe. Designed for per-EventLoop use
 * via [createForEventLoop], which returns a new instance with its own
 * freelist. No locking is needed because each EventLoop is single-threaded.
 *
 * ```
 * val engine = KqueueEngine(IoEngineConfig(allocator = SlabAllocator()))
 * // Engine internally calls allocator.createForEventLoop() per EventLoop
 * ```
 *
 * @param bufferSize Size of pooled buffers in bytes. Requests matching
 *                   this size are served from the pool. Other sizes
 *                   fall back to fresh allocation.
 * @param maxPoolSize Maximum number of buffers to retain in the pool.
 *                    Excess buffers are freed immediately on release.
 */
class SlabAllocator(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
) : BufferAllocator {

    private val pool = ArrayDeque<NativeBuf>(maxPoolSize)

    /**
     * Returns a new [SlabAllocator] instance for a single EventLoop thread.
     *
     * Each instance has its own independent freelist, so no locking is
     * needed. The [bufferSize] and [maxPoolSize] settings are inherited.
     */
    override fun createForEventLoop(): BufferAllocator =
        SlabAllocator(bufferSize, maxPoolSize)

    @Suppress("NativeBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): NativeBuf {
        val buf = if (capacity == bufferSize && pool.isNotEmpty()) {
            pool.removeLast().also { it.resetForReuse() }
        } else {
            NativeBuf(capacity)
        }
        buf.deallocator = ::returnToPool
        return buf
    }

    private fun returnToPool(buf: NativeBuf) {
        if (buf.capacity == bufferSize && pool.size < maxPoolSize) {
            pool.addLast(buf)
        } else {
            buf.close()
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val DEFAULT_MAX_POOL_SIZE = 256
    }
}
