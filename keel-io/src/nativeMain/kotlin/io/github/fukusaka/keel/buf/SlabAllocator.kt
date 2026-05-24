@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
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
 * **Per-EventLoop pooling**: [createForEventLoop] returns a fresh child
 * allocator with the parent's size classes propagated but per-pool capacity
 * capped at `LOCAL_POOL_SLOTS` (8), so each EventLoop owns its own pool
 * confined to a single thread. The parent allocator (the instance passed
 * to `IoEngineConfig`) is used only for size-class registration at startup;
 * the per-EL children perform the actual allocations.
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

    private val poolOwner: IoBufOwner = PoolOwner { buf -> returnToPool(buf as NativeIoBuf) }

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf {
        val recycled: NativeIoBuf? = withSpinLock {
            val pool = pools[capacity]
            if (pool != null && pool.list.isNotEmpty()) {
                pool.list.removeLast()
            } else {
                null
            }
        }
        if (recycled != null) {
            recycled.resetForReuse()
            recycled.owner = poolOwner
            return recycled
        }
        val fresh = NativeIoBuf(capacity)
        fresh.owner = poolOwner
        return fresh
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
        if (length == 0) return null
        val pinned = bytes.pin()
        val ptr = pinned.addressOf(offset)
        return NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length, owner = ExternalWrapOwner { pinned.unpin() })
    }

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        sliceDefaultIoBuf(source, offset, length)

    private fun returnToPool(buf: NativeIoBuf) {
        val rejected = withSpinLock {
            val pool = pools[buf.capacity]
            if (pool != null && pool.list.size < pool.maxSlots) {
                pool.list.addLast(buf)
                false
            } else {
                true
            }
        }
        // Pool full or no class for this size: free the backing directly.
        // refCount is already zero (we are inside PoolOwner.release).
        if (rejected) buf.freeBacking()
    }

    /**
     * Returns the native pointers and capacities of all pooled buffers.
     *
     * Used by io_uring to register buffers with the kernel for SEND_ZC_FIXED.
     * Each pair is (CPointer<ByteVar>, capacity). Returns empty list if no
     * buffers are currently pooled.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun nativePooledBuffers(): List<Pair<kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>, Int>> {
        return withSpinLock {
            val result = mutableListOf<Pair<kotlinx.cinterop.CPointer<kotlinx.cinterop.ByteVar>, Int>>()
            for ((_, pool) in pools) {
                for (buf in pool.list) {
                    result.add(buf.unsafePointer to buf.capacity)
                }
            }
            result
        }
    }

    private class Pool(val maxSlots: Int) {
        val list = ArrayDeque<NativeIoBuf>(maxSlots)
    }

    companion object {
        /** Standard pooled buffer size served through `standardMemorySource`. */
        private const val SEGMENT_SIZE = 8192
        private const val DEFAULT_BUFFER_SIZE = SEGMENT_SIZE
        private const val DEFAULT_POOL_SLOTS = 16
        private const val LOCAL_POOL_SLOTS = 8
        private const val DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 // 256 KiB
    }
}
