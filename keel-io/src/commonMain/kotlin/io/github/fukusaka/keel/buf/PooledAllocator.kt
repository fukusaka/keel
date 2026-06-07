@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

/**
 * Common pool-based [BufferAllocator] skeleton shared by the platform pools
 * ([SlabAllocator] on Native, [PooledDirectAllocator] on JVM).
 *
 * Holds the cross-platform machinery — the size-class lookup table, the per-class
 * budget, [registerPoolSize], [createForEventLoop] propagation, and the
 * allocate / return-to-pool routing — while delegating the two platform- and
 * strategy-specific seams to subclasses:
 *
 * - [newBuffer] constructs a fresh backing buffer (`NativeIoBuf` / `DirectIoBuf`).
 * - [newFreelist] constructs the per-class [Freelist] (the pluggable concurrency
 *   strategy: spin lock / Treiber / mutex / versioned-index — see [Freelist]).
 *
 * **Size-class lookup** is a copy-on-write table of parallel arrays
 * (`sizes` / `pools` / `maxSlots`) published through a single `@Volatile` ref. On
 * the hot path [allocate] / [returnToPool] do a lock-free volatile read + a
 * linear scan over the (tiny, 1–3 entry) size-class set — measured faster than a
 * boxed `HashMap` lookup, the dominant allocator cost being `Int` boxing, not the
 * scan (see `benchmark --bench=poolmap-variants`).
 *
 * **Thread safety**: [allocate] / [returnToPool] are safe for concurrent callers
 * to the extent the chosen [Freelist] is (the size-class read is a lock-free
 * volatile load). [registerPoolSize] is a copy-on-write writer and must not be
 * called concurrently with itself on the same instance — it is invoked at
 * construction and at per-EventLoop setup (bind / TLS handler) on the owning
 * thread, never on the hot path.
 *
 * @param maxTotalBytes Safety valve: maximum total bytes across all pool classes.
 * @param freelistFactory Optional override for the per-size-class [Freelist] strategy.
 *   When `null` (default), each subclass selects its own platform-tuned strategy
 *   (`SpinLockFreelist` on Native, intrusive Treiber on JVM). Pass a non-null
 *   [FreelistFactory] to swap in a different `Freelist` implementation — for
 *   example `::MutexFreelist` for an arbitrary-concurrency public allocator, or
 *   a custom strategy. The factory is invoked once per registered size class
 *   with that class's `maxSlots`, and is forwarded to every per-EventLoop child
 *   produced by [createForEventLoop].
 */
abstract class PooledAllocator(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    /**
     * Exposed `protected` so subclasses can forward the same factory to
     * per-EventLoop children produced by [createChild]. Treat as read-only.
     */
    protected val freelistFactory: FreelistFactory? = null,
) : BufferAllocator {

    /** Immutable size-class snapshot; replaced wholesale on [registerPoolSize] (COW). */
    private class Table(val sizes: IntArray, val pools: Array<Freelist>, val maxSlots: IntArray)

    @kotlin.concurrent.Volatile
    private var table: Table = Table(IntArray(0), emptyArray(), IntArray(0))

    private val poolOwner: IoBufOwner = PoolOwner { buf -> returnToPool(buf) }

    /** Constructs a fresh backing buffer of exactly [capacity] bytes (platform seam). */
    protected abstract fun newBuffer(capacity: Int): IoBuf

    /**
     * Constructs the per-size-class freelist. The default implementation honours
     * the [freelistFactory] passed to the constructor and falls back to
     * [defaultFreelist] when no factory is set, so subclasses normally only need
     * to override [defaultFreelist] to declare their platform-tuned strategy.
     */
    protected open fun newFreelist(maxSlots: Int): Freelist =
        freelistFactory?.create(maxSlots) ?: defaultFreelist(maxSlots)

    /** Platform-tuned default `Freelist` strategy (subclass seam). */
    protected abstract fun defaultFreelist(maxSlots: Int): Freelist

    /**
     * Constructs a sibling instance for a single EventLoop (platform seam).
     * Subclasses must propagate the same [freelistFactory] so per-EL children
     * inherit the user-selected strategy.
     */
    protected abstract fun createChild(maxTotalBytes: Long): PooledAllocator

    final override fun registerPoolSize(size: Int, maxSlots: Int) {
        val cur = table
        if (cur.sizes.contains(size)) return
        val currentBudget = sumBudget(cur)
        val effectiveMaxSlots = if (currentBudget + size.toLong() * maxSlots > maxTotalBytes) {
            ((maxTotalBytes - currentBudget) / size).toInt().coerceAtLeast(1)
        } else {
            maxSlots
        }
        val n = cur.sizes.size
        val freelist = newFreelist(effectiveMaxSlots)
        val sizes = cur.sizes.copyOf(n + 1)
        val slots = cur.maxSlots.copyOf(n + 1)
        // Build the pools array fully populated before any concurrent read can
        // see it: copyOf on Array<Freelist> would leave a null sentinel at the
        // tail until the assignment below, which is unobservable here (the
        // writer is single-threaded by contract) but the explicit Array(n + 1)
        // form is easier to audit.
        val pools = Array(n + 1) { i -> if (i < n) cur.pools[i] else freelist }
        sizes[n] = size
        slots[n] = effectiveMaxSlots
        table = Table(sizes, pools, slots)
    }

    private fun sumBudget(t: Table): Long {
        var sum = 0L
        for (i in t.sizes.indices) sum += t.sizes[i].toLong() * t.maxSlots[i]
        return sum
    }

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    final override fun allocate(capacity: Int): IoBuf {
        val t = table
        val sizes = t.sizes
        for (i in sizes.indices) {
            if (sizes[i] == capacity) {
                val recycled = t.pools[i].pop()
                if (recycled != null) {
                    (recycled as AbstractIoBuf).resetForReuse()
                    recycled.owner = poolOwner
                    return recycled
                }
                break // class exists but empty -> fresh allocation
            }
        }
        val fresh = newBuffer(capacity)
        (fresh as AbstractIoBuf).owner = poolOwner
        return fresh
    }

    private fun returnToPool(buf: IoBuf) {
        val cap = buf.capacity
        val t = table
        val sizes = t.sizes
        for (i in sizes.indices) {
            if (sizes[i] == cap) {
                if (t.pools[i].push(buf)) return
                break // pool full -> free backing
            }
        }
        // No class for this size, or pool full: free the backing directly.
        // refCount is already zero (we are inside PoolOwner.release).
        (buf as AbstractIoBuf).freeBacking()
    }

    final override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        sliceDefaultIoBuf(source, offset, length)

    final override fun createForEventLoop(): BufferAllocator =
        createChild(maxTotalBytes).also { child ->
            val t = table
            for (i in t.sizes.indices) {
                child.registerPoolSize(t.sizes[i], t.maxSlots[i].coerceAtMost(LOCAL_POOL_SLOTS))
            }
        }

    /**
     * Snapshot of every pooled buffer currently held across all size classes,
     * without removing them.
     *
     * Used by engines that need to enumerate the pool's resident buffers — most
     * notably io_uring, which registers each Native-backed pooled buffer with
     * the kernel for `SEND_ZC_FIXED` once at startup. Engines downcast to
     * [PooledAllocator] (the common shape) and filter the returned list for the
     * platform-specific carrier they need (e.g. on Linux, `NativeIoBuf` /
     * `NativePointerAccess`), so an out-of-tree [PooledAllocator] subclass is
     * automatically supported as long as it returns buffers of that carrier.
     *
     * Not a hot-path call — only invoked at engine bind / per-EventLoop setup.
     */
    fun pooledBuffers(): List<IoBuf> {
        val result = mutableListOf<IoBuf>()
        val t = table
        for (i in t.pools.indices) t.pools[i].snapshotInto(result)
        return result
    }

    companion object {
        /** Standard 8 KiB pooled buffer size class registered by the platform pools. */
        const val SEGMENT_SIZE: Int = 8192
        internal const val DEFAULT_POOL_SLOTS = 16
        internal const val LOCAL_POOL_SLOTS = 8
        internal const val DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 // 256 KiB
    }
}
