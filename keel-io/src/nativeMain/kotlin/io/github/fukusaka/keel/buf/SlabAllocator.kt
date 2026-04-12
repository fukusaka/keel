package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.plus
import kotlin.concurrent.AtomicReference

/**
 * Pool-based [BufferAllocator] for Native targets with multi-size-class support.
 *
 * Maintains spin-lock-protected freelists of [NativeIoBuf] instances,
 * one per registered size class. Size classes are registered dynamically
 * via [registerPoolSize]. The default 8 KiB class is registered at
 * construction for backward compatibility.
 *
 * **Thread safety**: spin lock per pool. EventLoop-based engines
 * (kqueue/epoll) access from a single thread (always uncontended).
 *
 * @param maxTotalBytes Maximum total bytes across all pool classes.
 *   Acts as a safety valve. Default: 256 KiB.
 */
class SlabAllocator(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) : BufferAllocator {

    private val pools = HashMap<Int, Pool>()
    private val lock = AtomicReference(false)

    private inline fun <T> withSpinLock(block: () -> T): T {
        while (!lock.compareAndSet(false, true)) { /* spin */ }
        try {
            return block()
        } finally {
            lock.value = false
        }
    }

    init {
        registerPoolSize(DEFAULT_BUFFER_SIZE, DEFAULT_POOL_SLOTS)
    }

    override fun createForEventLoop(): BufferAllocator =
        SlabAllocator(maxTotalBytes).also { child ->
            withSpinLock {
                for ((size, pool) in pools) {
                    child.registerPoolSize(size, pool.maxSlots.coerceAtMost(LOCAL_POOL_SLOTS))
                }
            }
        }

    // Atomic under spin lock: containsKey + budget + insert is a single critical section.
    override fun registerPoolSize(size: Int, maxSlots: Int) {
        withSpinLock {
            if (pools.containsKey(size)) return
            val currentBudget = pools.entries.sumOf { (s, p) -> s.toLong() * p.maxSlots }
            val effectiveMaxSlots = if (currentBudget + size.toLong() * maxSlots > maxTotalBytes) {
                ((maxTotalBytes - currentBudget) / size).toInt().coerceAtLeast(1)
            } else {
                maxSlots
            }
            pools[size] = Pool(effectiveMaxSlots)
        }
    }

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf {
        val buf: NativeIoBuf = withSpinLock {
            val pool = pools[capacity]
            if (pool != null && pool.list.isNotEmpty()) {
                pool.list.removeLast().also { it.resetForReuse() }
            } else {
                null
            }
        } ?: NativeIoBuf(capacity)
        buf.deallocator = ::returnToPool
        return buf
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

    private fun returnToPool(buf: IoBuf) {
        val closed = withSpinLock {
            val pool = pools[buf.capacity]
            if (pool != null && pool.list.size < pool.maxSlots) {
                pool.list.addLast(buf as NativeIoBuf)
                false
            } else {
                true
            }
        }
        if (closed) buf.close()
    }

    private class Pool(val maxSlots: Int) {
        val list = ArrayDeque<NativeIoBuf>(maxSlots)
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val DEFAULT_POOL_SLOTS = 16
        private const val LOCAL_POOL_SLOTS = 8
        private const val DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 // 256 KiB
    }
}
