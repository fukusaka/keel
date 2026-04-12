package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.plus
import kotlin.concurrent.AtomicReference

/**
 * Pool-based [BufferAllocator] for Native targets.
 *
 * Maintains a freelist of pre-sized [IoBuf] instances. When a buffer
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

    private val pool = ArrayDeque<NativeIoBuf>(maxPoolSize)
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

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf {
        val buf: NativeIoBuf = if (capacity == bufferSize) {
            withSpinLock {
                if (pool.isNotEmpty()) pool.removeLast().also { it.resetForReuse() }
                else null
            }
        } else {
            null
        } ?: NativeIoBuf(capacity)
        buf.deallocator = ::returnToPool
        return buf
    }

    private fun returnToPool(buf: IoBuf) {
        if (buf.capacity != bufferSize) {
            buf.close()
            return
        }
        val closed = withSpinLock {
            if (pool.size < maxPoolSize) {
                pool.addLast(buf as NativeIoBuf)
                false
            } else {
                true
            }
        }
        if (closed) buf.close()
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
        if (length == 0) return null
        val pinned = bytes.pin()
        @Suppress("UnsafeCallOnNullableType")
        val ptr = pinned.addressOf(offset)!!
        return NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length) { _ ->
            pinned.unpin()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf {
        if (length == 0) return EmptyIoBuf
        source.retain()
        @Suppress("UnsafeCallOnNullableType")
        val ptr = ((source as NativePointerAccess).unsafePointer + offset)!!
        return NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length) { _ ->
            source.release()
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val DEFAULT_MAX_POOL_SIZE = 256
    }
}
