package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.pin
import kotlinx.cinterop.plus
import kotlin.concurrent.AtomicReference

/**
 * Pool-based [BufferAllocator] for Native targets with size-class pooling.
 *
 * Maintains a static table of spin-lock-protected freelists of [NativeIoBuf]
 * instances, one (initially empty) freelist per jemalloc-style size class
 * (see [sizeClasses]). Every [allocate] request is rounded up to a size class
 * via [normalizeToSizeClass], so every request up to [MAX_SIZE_CLASS] hits a
 * pool; requests above the ceiling are served un-pooled at their exact size.
 *
 * **Thread safety**: a single spin lock guards the freelists and the
 * [pooledBytes] accounting. EventLoop-based engines (kqueue/epoll) access from
 * a single thread (always uncontended).
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
class SlabAllocator(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
) : BufferAllocator {

    // Static table: one (initially empty) pool per size class. An empty pool
    // holds zero buffers and costs only a freelist head.
    private val pools: HashMap<Int, Pool> = HashMap<Int, Pool>().apply {
        for (cls in sizeClasses()) put(cls, Pool(DEFAULT_POOL_SLOTS))
    }
    private val lock = AtomicReference(false)

    // Running total of bytes retained across all pools. Mutated only under
    // the spin lock; the soft maxTotalBytes valve is enforced in returnToPool.
    private var pooledBytes: Long = 0

    private inline fun <T> withSpinLock(block: () -> T): T {
        while (!lock.compareAndSet(false, true)) { /* spin */ }
        try {
            return block()
        } finally {
            lock.value = false
        }
    }

    override fun createForEventLoop(): BufferAllocator =
        SlabAllocator(maxTotalBytes).also { child ->
            withSpinLock {
                for ((size, pool) in pools) {
                    child.registerPoolSize(size, pool.maxSlots.coerceAtMost(LOCAL_POOL_SLOTS))
                }
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
        val cls = normalizeToSizeClass(size)
        withSpinLock {
            val pool = pools[cls] ?: return
            if (maxSlots > pool.maxSlots) pool.maxSlots = maxSlots
        }
    }

    private val poolOwner: IoBufMemoryOwner = PoolOwner(::returnToPool)

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf {
        val cls = normalizeToSizeClass(capacity)
        if (cls > MAX_SIZE_CLASS) {
            // Huge request: un-pooled, served at the exact requested capacity.
            // It still carries poolOwner so release routes to returnToPool,
            // which finds no matching pool and closes it.
            return NativeIoBuf(capacity, poolOwner)
        }
        val pooled: NativeIoBuf? = withSpinLock {
            val pool = pools[cls]
            if (pool != null && pool.list.isNotEmpty()) {
                pool.list.removeLast().also {
                    it.resetForReuse()
                    pooledBytes -= it.capacity.toLong()
                }
            } else {
                null
            }
        }
        return pooled ?: NativeIoBuf(cls, poolOwner)
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
        if (length == 0) return null
        val pinned = bytes.pin()
        @Suppress("UnsafeCallOnNullableType")
        val ptr = pinned.addressOf(offset)!!
        return NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length, memoryOwner = ExternalWrapOwner { pinned.unpin() })
    }

    @OptIn(ExperimentalForeignApi::class)
    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf {
        if (length == 0) return EmptyIoBuf
        source.retain()
        @Suppress("UnsafeCallOnNullableType")
        val ptr = ((source as NativePointerAccess).unsafePointer + offset)!!
        return NativeIoBuf.wrapExternal(ptr, length, bytesWritten = length, memoryOwner = SliceOwner(source))
    }

    private fun returnToPool(buf: IoBuf) {
        val capacity = buf.capacity.toLong()
        val closed = withSpinLock {
            val pool = pools[buf.capacity]
            if (pool != null && pool.list.size < pool.maxSlots && pooledBytes + capacity <= maxTotalBytes) {
                pool.list.addLast(buf as NativeIoBuf)
                pooledBytes += capacity
                false
            } else {
                true
            }
        }
        if (closed) buf.close()
    }

    /**
     * Returns the native pointers and capacities of all pooled buffers.
     *
     * Used by io_uring to register buffers with the kernel for SEND_ZC_FIXED.
     * Each pair is (CPointer<ByteVar>, capacity). Pooled buffers always have a
     * size-class capacity. Returns empty list if no buffers are currently
     * pooled.
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

    private class Pool(var maxSlots: Int) {
        val list = ArrayDeque<NativeIoBuf>(maxSlots)
    }

    companion object {
        private const val DEFAULT_POOL_SLOTS = 16
        private const val LOCAL_POOL_SLOTS = 8
        private const val DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 // 256 KiB
    }
}
