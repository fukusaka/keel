package io.github.fukusaka.keel.io

import kotlin.concurrent.AtomicReference

/**
 * Pool-based [BufferAllocator] for Native targets.
 *
 * Maintains a freelist of pre-sized [NativeBuf] instances. When a buffer
 * is released, it is returned to the pool instead of being freed. This
 * eliminates per-connection `nativeHeap.allocArray` / `nativeHeap.free`
 * overhead.
 *
 * **Thread safety**: thread-safe via spin lock. EventLoop-based engines
 * (kqueue/epoll) access the pool from a single thread, so the lock is
 * always uncontended (~5ns CAS). Push-model engines (NWConnection) may
 * access from multiple Dispatchers.Default workers when deferred flush
 * is enabled.
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
    private val lock = AtomicReference(false)

    private inline fun <T> withSpinLock(block: () -> T): T {
        while (!lock.compareAndSet(false, true)) { /* spin */ }
        try {
            return block()
        } finally {
            lock.value = false
        }
    }

    override fun createForEventLoop(): BufferAllocator =
        SlabAllocator(bufferSize, maxPoolSize)

    @Suppress("NativeBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): NativeBuf {
        val buf = if (capacity == bufferSize) {
            withSpinLock {
                if (pool.isNotEmpty()) pool.removeLast().also { it.resetForReuse() }
                else null
            }
        } else {
            null
        } ?: NativeBuf(capacity)
        buf.deallocator = ::returnToPool
        return buf
    }

    private fun returnToPool(buf: NativeBuf) {
        if (buf.capacity != bufferSize) {
            buf.close()
            return
        }
        val closed = withSpinLock {
            if (pool.size < maxPoolSize) {
                pool.addLast(buf)
                false
            } else {
                true
            }
        }
        if (closed) buf.close()
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val DEFAULT_MAX_POOL_SIZE = 256
    }
}
