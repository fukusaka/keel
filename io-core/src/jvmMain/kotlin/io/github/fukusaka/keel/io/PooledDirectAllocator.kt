package io.github.fukusaka.keel.io

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pool-based [BufferAllocator] for JVM targets.
 *
 * Maintains a freelist of [NativeBuf] instances backed by
 * [java.nio.ByteBuffer.allocateDirect]. DirectByteBuffer allocation
 * is expensive (JNI call + OS mmap), so reusing buffers significantly
 * reduces per-connection overhead.
 *
 * **Thread safety**: thread-safe. Uses [ConcurrentLinkedDeque] for
 * lock-free pool access. This is required because NIO's
 * `appDispatcher = Dispatchers.Default` causes `allocate()` and
 * `returnToPool()` to run on different ForkJoinPool worker threads
 * when deferred flush is enabled in [BufferedSuspendSink].
 *
 * Native [SlabAllocator] does not need thread safety because Native
 * engines run `appDispatcher` on the EventLoop thread (single-threaded).
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

    private val pool = ConcurrentLinkedDeque<NativeBuf>()
    private val poolSize = AtomicInteger(0)

    override fun createForEventLoop(): BufferAllocator =
        PooledDirectAllocator(bufferSize, maxPoolSize)

    @Suppress("NativeBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): NativeBuf {
        val buf = if (capacity == bufferSize) {
            pool.pollLast()?.also {
                poolSize.decrementAndGet()
                it.resetForReuse()
            }
        } else {
            null
        } ?: NativeBuf(capacity)
        buf.deallocator = ::returnToPool
        return buf
    }

    private fun returnToPool(buf: NativeBuf) {
        if (buf.capacity == bufferSize && poolSize.get() < maxPoolSize) {
            pool.addLast(buf)
            poolSize.incrementAndGet()
        } else {
            buf.close()
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val DEFAULT_MAX_POOL_SIZE = 256
    }
}
