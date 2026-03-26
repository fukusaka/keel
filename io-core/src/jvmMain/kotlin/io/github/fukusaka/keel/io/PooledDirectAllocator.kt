package io.github.fukusaka.keel.io

/**
 * Pool-based [BufferAllocator] for JVM targets.
 *
 * Maintains a freelist of [NativeBuf] instances backed by
 * [java.nio.ByteBuffer.allocateDirect]. DirectByteBuffer allocation
 * is expensive (JNI call + OS mmap), so reusing buffers significantly
 * reduces per-connection overhead.
 *
 * **Thread safety**: not thread-safe. Designed for per-EventLoop use
 * via [createForEventLoop], which returns a new instance with its own
 * freelist. No locking is needed because each EventLoop is single-threaded.
 *
 * ```
 * val engine = NioEngine(IoEngineConfig(allocator = PooledDirectAllocator()))
 * // Engine internally calls allocator.createForEventLoop() per EventLoop
 * ```
 *
 * @param bufferSize Size of pooled buffers in bytes. Requests matching
 *                   this size are served from the pool. Other sizes
 *                   fall back to fresh allocation.
 * @param maxPoolSize Maximum number of buffers to retain in the pool.
 *                    Excess buffers are GC-collected on release.
 */
class PooledDirectAllocator(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
) : BufferAllocator {

    private val pool = ArrayDeque<NativeBuf>(maxPoolSize)

    /**
     * Returns a new [PooledDirectAllocator] instance for a single EventLoop thread.
     *
     * Each instance has its own independent freelist, so no locking is
     * needed. The [bufferSize] and [maxPoolSize] settings are inherited.
     */
    override fun createForEventLoop(): BufferAllocator =
        PooledDirectAllocator(bufferSize, maxPoolSize)

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
