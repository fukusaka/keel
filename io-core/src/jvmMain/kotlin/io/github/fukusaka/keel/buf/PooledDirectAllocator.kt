package io.github.fukusaka.keel.buf

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pool-based [BufferAllocator] for JVM targets.
 *
 * Maintains a lock-free freelist of [IoBuf] instances backed by
 * [java.nio.ByteBuffer.allocateDirect] using an intrusive Treiber stack.
 * Each [IoBuf.nextLink] field links to the next buffer in the freelist,
 * eliminating wrapper node allocations that [java.util.concurrent.ConcurrentLinkedDeque]
 * would require.
 *
 * DirectByteBuffer allocation is expensive (JNI call + OS mmap), so reusing
 * buffers significantly reduces per-connection overhead.
 *
 * **Thread safety**: lock-free via [AtomicReference] CAS on the stack head.
 * Required because NIO's `appDispatcher = Dispatchers.Default` causes
 * `allocate()` and `returnToPool()` to run on different ForkJoinPool
 * worker threads when deferred flush is enabled in [BufferedSuspendSink].
 *
 * @param bufferSize Size of pooled buffers in bytes. Requests matching
 *                   this size are served from the pool. Other sizes
 *                   fall back to fresh allocation.
 * @param maxPoolSize Maximum number of buffers to retain in the pool.
 *                    Strictly enforced via increment-then-check on [poolSize].
 *                    Excess buffers are GC-collected.
 */
class PooledDirectAllocator(
    private val bufferSize: Int = DEFAULT_BUFFER_SIZE,
    private val maxPoolSize: Int = DEFAULT_MAX_POOL_SIZE,
) : BufferAllocator {

    private val head = AtomicReference<DirectIoBuf?>(null)
    private val poolSize = AtomicInteger(0)

    override fun createForEventLoop(): BufferAllocator =
        PooledDirectAllocator(bufferSize, maxPoolSize)

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf {
        val buf: DirectIoBuf = if (capacity == bufferSize) {
            pop()?.also { it.resetForReuse() }
        } else {
            null
        } ?: DirectIoBuf(capacity)
        buf.deallocator = ::returnToPool
        return buf
    }

    private fun pop(): DirectIoBuf? {
        while (true) {
            val cur = head.get() ?: return null
            if (head.compareAndSet(cur, cur.nextLink as DirectIoBuf?)) {
                cur.nextLink = null  // detach from freelist
                poolSize.decrementAndGet()
                return cur
            }
        }
    }

    private fun returnToPool(buf: IoBuf) {
        if (buf.capacity != bufferSize) {
            buf.close()
            return
        }
        val poolBuf = buf as DirectIoBuf
        // Increment-then-check: strictly enforces maxPoolSize even under
        // concurrent access. If the pool is full, undo the increment.
        val newSize = poolSize.incrementAndGet()
        if (newSize <= maxPoolSize) {
            while (true) {
                val cur = head.get()
                poolBuf.nextLink = cur
                if (head.compareAndSet(cur, poolBuf)) return
            }
        } else {
            poolSize.decrementAndGet()
            buf.close()
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val DEFAULT_MAX_POOL_SIZE = 256
    }
}
