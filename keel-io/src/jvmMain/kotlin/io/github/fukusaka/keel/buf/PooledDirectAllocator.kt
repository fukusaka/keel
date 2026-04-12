package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Pool-based [BufferAllocator] for JVM targets with multi-size-class support.
 *
 * Maintains lock-free freelists of [IoBuf] instances backed by
 * [java.nio.ByteBuffer.allocateDirect], one per registered size class.
 * Each freelist is an intrusive Treiber stack using [IoBuf.nextLink],
 * eliminating wrapper node allocations.
 *
 * Size classes are registered dynamically via [registerPoolSize].
 * The default 8 KiB class is registered at construction for backward
 * compatibility with engine read buffers and [BufferedSuspendSink].
 *
 * **Thread safety**: lock-free via [AtomicReference] CAS on each stack head.
 *
 * @param maxTotalBytes Maximum total bytes across all pool classes.
 *   Acts as a safety valve; per-class [maxSlots] in [registerPoolSize]
 *   is the primary control. Default: 256 KiB.
 */
class PooledDirectAllocator(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) : BufferAllocator {

    private val pools = ConcurrentHashMap<Int, Pool>()

    init {
        // Default 8 KiB class for backward compatibility.
        registerPoolSize(DEFAULT_BUFFER_SIZE, DEFAULT_POOL_SLOTS)
    }

    override fun createForEventLoop(): BufferAllocator =
        PooledDirectAllocator(maxTotalBytes).also { child ->
            // Propagate registered size classes with local pool sizes.
            for ((size, pool) in pools) {
                child.registerPoolSize(size, pool.maxSlots.coerceAtMost(LOCAL_POOL_SLOTS))
            }
        }

    // Not synchronized: relies on single-thread ownership per instance.
    // Parent allocator is only mutated in init (constructor thread).
    // Child allocators (from createForEventLoop) are owned by one EventLoop thread.
    override fun registerPoolSize(size: Int, maxSlots: Int) {
        if (pools.containsKey(size)) return
        val currentBudget = pools.entries.sumOf { (s, p) -> s.toLong() * p.maxSlots }
        val effectiveMaxSlots = if (currentBudget + size.toLong() * maxSlots > maxTotalBytes) {
            ((maxTotalBytes - currentBudget) / size).toInt().coerceAtLeast(1)
        } else {
            maxSlots
        }
        pools.putIfAbsent(size, Pool(effectiveMaxSlots))
    }

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf {
        val pool = pools[capacity]
        val buf: DirectIoBuf = if (pool != null) {
            pool.pop()?.also { it.resetForReuse() }
        } else {
            null
        } ?: DirectIoBuf(capacity)
        buf.deallocator = ::returnToPool
        return buf
    }

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
        if (length == 0) return null
        val heapBuffer = if (offset == 0 && length == bytes.size) {
            ByteBuffer.wrap(bytes)
        } else {
            ByteBuffer.wrap(bytes, offset, length).slice()
        }
        return DirectIoBuf.wrapExternal(heapBuffer, bytesWritten = length)
    }

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf {
        if (length == 0) return EmptyIoBuf
        source.retain()
        val srcBuf = (source as DirectIoBuf).unsafeBuffer
        val view = srcBuf.duplicate().apply {
            position(offset)
            limit(offset + length)
        }.slice()
        return DirectIoBuf.wrapExternal(view, bytesWritten = length).also {
            it.deallocator = { _ -> source.release() }
        }
    }

    private fun returnToPool(buf: IoBuf) {
        val pool = pools[buf.capacity]
        if (pool != null) {
            pool.push(buf as DirectIoBuf)
        } else {
            buf.close()
        }
    }

    /**
     * Lock-free Treiber stack for a single size class.
     */
    private class Pool(val maxSlots: Int) {
        val head = AtomicReference<DirectIoBuf?>(null)
        val size = AtomicInteger(0)

        fun pop(): DirectIoBuf? {
            while (true) {
                val cur = head.get() ?: return null
                if (head.compareAndSet(cur, cur.nextLink as DirectIoBuf?)) {
                    cur.nextLink = null
                    size.decrementAndGet()
                    return cur
                }
            }
        }

        fun push(buf: DirectIoBuf) {
            val newSize = size.incrementAndGet()
            if (newSize <= maxSlots) {
                while (true) {
                    val cur = head.get()
                    buf.nextLink = cur
                    if (head.compareAndSet(cur, buf)) return
                }
            } else {
                size.decrementAndGet()
                buf.close()
            }
        }
    }

    companion object {
        private const val DEFAULT_BUFFER_SIZE = 8192
        private const val DEFAULT_POOL_SLOTS = 16
        private const val LOCAL_POOL_SLOTS = 8
        private const val DEFAULT_MAX_TOTAL_BYTES = 512L * 1024 // 512 KiB
    }
}
