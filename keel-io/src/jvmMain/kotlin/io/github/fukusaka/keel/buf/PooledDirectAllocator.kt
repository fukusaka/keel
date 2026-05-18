package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Pool-based [BufferAllocator] for JVM targets with size-class pooling.
 *
 * Maintains a static table of lock-free freelists of [IoBuf] instances backed
 * by [java.nio.ByteBuffer.allocateDirect], one (initially empty) freelist per
 * jemalloc-style size class (see [sizeClasses]). Each freelist is an intrusive
 * Treiber stack using [IoBuf.nextLink], eliminating wrapper node allocations.
 *
 * Every [allocate] request is rounded up to a size class via
 * [normalizeToSizeClass], so every request up to [MAX_SIZE_CLASS] hits a pool;
 * requests above the ceiling are served un-pooled at their exact size.
 *
 * **Thread safety**: lock-free via [AtomicReference] CAS on each stack head.
 *
 * **Per-EventLoop pooling**: [createForEventLoop] returns a fresh child
 * allocator that builds its own size-class table and copies the parent's
 * per-class [Pool.maxSlots], capped at `LOCAL_POOL_SLOTS` (8), so each
 * EventLoop owns its own pool confined to a single thread. The parent
 * allocator (the instance passed to `IoEngineConfig`) is used only for
 * per-class capacity tuning at startup; the per-EL children perform the
 * actual allocations.
 *
 * @param maxTotalBytes Maximum total bytes retained across all pool classes.
 *   Acts as a soft valve enforced at release time via [pooledBytes].
 *   Default: 256 KiB.
 */
class PooledDirectAllocator(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) : BufferAllocator {

    // Static table: one (initially empty) pool per size class. An empty pool
    // holds zero buffers and costs only a freelist head.
    private val pools: ConcurrentHashMap<Int, Pool> = ConcurrentHashMap<Int, Pool>().apply {
        for (cls in sizeClasses()) put(cls, Pool(DEFAULT_POOL_SLOTS))
    }

    // Running total of bytes retained across all pools. A benign
    // check-then-add race is acceptable here: maxTotalBytes is a soft valve,
    // not a hard limit.
    private val pooledBytes = AtomicLong(0)

    override fun createForEventLoop(): BufferAllocator =
        PooledDirectAllocator(maxTotalBytes).also { child ->
            for ((size, pool) in pools) {
                child.registerPoolSize(size, pool.maxSlots.coerceAtMost(LOCAL_POOL_SLOTS))
            }
        }

    /**
     * Bumps the retained-buffer capacity of the size class covering [size].
     *
     * The size-class table is static, so every class already exists; this
     * call only raises the [Pool.maxSlots] of the class that [size] rounds up
     * to via [normalizeToSizeClass] (never lowers it). The global
     * [maxTotalBytes] valve remains the real ceiling. Callers that previously
     * "registered" a size now simply hint that more buffers of that class
     * should be retained.
     */
    override fun registerPoolSize(size: Int, maxSlots: Int) {
        val pool = pools[normalizeToSizeClass(size)] ?: return
        if (maxSlots > pool.maxSlots) pool.maxSlots = maxSlots
    }

    private val poolOwner: IoBufMemoryOwner = PoolOwner(::returnToPool)

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf {
        val cls = normalizeToSizeClass(capacity)
        if (cls > MAX_SIZE_CLASS) {
            // Huge request: un-pooled, served at the exact requested capacity.
            // It still carries poolOwner so release routes to returnToPool,
            // which finds no matching pool and closes it.
            return DirectIoBuf(capacity, poolOwner)
        }
        val pool = pools[cls]
        val pooled: DirectIoBuf? = pool?.pop()?.also {
            it.resetForReuse()
            pooledBytes.addAndGet(-it.capacity.toLong())
        }
        return pooled ?: DirectIoBuf(cls, poolOwner)
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
        return DirectIoBuf.wrapExternal(view, bytesWritten = length, memoryOwner = SliceOwner(source))
    }

    private fun returnToPool(buf: IoBuf) {
        val capacity = buf.capacity.toLong()
        val pool = pools[buf.capacity]
        // Soft budget check: a benign check-then-add race is tolerated.
        if (pool != null && pooledBytes.get() + capacity <= maxTotalBytes) {
            if (pool.push(buf as DirectIoBuf)) {
                pooledBytes.addAndGet(capacity)
            }
        } else {
            buf.close()
        }
    }

    /**
     * Lock-free Treiber stack for a single size class.
     */
    private class Pool(@Volatile var maxSlots: Int) {
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

        /** Pushes [buf] onto the freelist. Returns `false` (and closes [buf]) if the pool is full. */
        fun push(buf: DirectIoBuf): Boolean {
            val newSize = size.incrementAndGet()
            if (newSize <= maxSlots) {
                while (true) {
                    val cur = head.get()
                    buf.nextLink = cur
                    if (head.compareAndSet(cur, buf)) return true
                }
            } else {
                size.decrementAndGet()
                buf.close()
                return false
            }
        }
    }

    companion object {
        private const val DEFAULT_POOL_SLOTS = 16
        private const val LOCAL_POOL_SLOTS = 8
        private const val DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 // 256 KiB
    }
}
