@file:OptIn(UnsafeIoBufApi::class)

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
 * Each freelist is an intrusive Treiber stack of [Segment]s linked via
 * [Segment.nextLink], eliminating wrapper node allocations.
 *
 * Size classes are registered dynamically via [registerPoolSize].
 * The default 8 KiB class is registered at construction for backward
 * compatibility with engine read buffers and [BufferedSuspendSink].
 *
 * **Thread safety**: lock-free via [AtomicReference] CAS on each stack head.
 *
 * **Per-EventLoop pooling**: [createForEventLoop] returns a fresh child
 * allocator with the parent's size classes propagated but per-pool capacity
 * capped at `LOCAL_POOL_SLOTS` (8), so each EventLoop owns its own pool
 * confined to a single thread. The parent allocator (the instance passed
 * to `IoEngineConfig`) is used only for size-class registration at startup;
 * the per-EL children perform the actual allocations.
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

    private val poolOwner: SegmentOwner = PoolOwner(::returnToPool)

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf {
        val pool = pools[capacity]
        val seg: Segment? = pool?.pop()
        if (seg != null) {
            seg.resetForReuse()
            val view = checkNotNull(seg.view as? DirectIoBuf) { "pooled segment has no DirectIoBuf view" }
            view.clear()
            return view
        }
        return DirectIoBuf.overSegment(newSegment(capacity), poolOwner)
    }

    /**
     * Allocates a fresh direct [Segment] of [capacity] bytes for a pool
     * miss. Standard-size and "huge bypass" (larger than the pooled
     * class) requests follow the same path; the allocation is
     * `ByteBuffer.allocateDirect(capacity)` either way, and pool
     * recycling is handled at a higher level via [PoolOwner].
     */
    private fun newSegment(capacity: Int): Segment =
        Segment(DirectByteBufferBacking(ByteBuffer.allocateDirect(capacity)), capacity)

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? {
        if (length == 0) return null
        val heapBuffer = if (offset == 0 && length == bytes.size) {
            ByteBuffer.wrap(bytes)
        } else {
            ByteBuffer.wrap(bytes, offset, length).slice()
        }
        return DirectIoBuf.wrapExternal(heapBuffer, bytesWritten = length)
    }

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        sliceDefaultIoBuf(source, offset, length)

    private fun returnToPool(seg: Segment) {
        val pool = pools[seg.capacity]
        if (pool != null) {
            pool.push(seg)
        } else {
            // No class for this size: free the backing directly.
            // The segment's refCount is already zero (we are inside PoolOwner.release).
            seg.backing.free()
        }
    }

    /**
     * Lock-free Treiber stack of [Segment]s for a single size class.
     *
     * Uses [Segment.nextLink] as the intrusive freelist link, avoiding
     * wrapper node allocations.
     */
    private class Pool(val maxSlots: Int) {
        val head = AtomicReference<Segment?>(null)
        val size = AtomicInteger(0)

        fun pop(): Segment? {
            while (true) {
                val cur = head.get() ?: return null
                if (head.compareAndSet(cur, cur.nextLink)) {
                    cur.nextLink = null
                    size.decrementAndGet()
                    return cur
                }
            }
        }

        fun push(seg: Segment) {
            val newSize = size.incrementAndGet()
            if (newSize <= maxSlots) {
                while (true) {
                    val cur = head.get()
                    seg.nextLink = cur
                    if (head.compareAndSet(cur, seg)) return
                }
            } else {
                size.decrementAndGet()
                // Pool full: free the backing directly. refCount is already 0.
                seg.backing.free()
            }
        }
    }

    companion object {
        /** Standard pooled segment size served through [standardMemorySource]. */
        private const val SEGMENT_SIZE = 8192
        private const val DEFAULT_BUFFER_SIZE = SEGMENT_SIZE
        private const val DEFAULT_POOL_SLOTS = 16
        private const val LOCAL_POOL_SLOTS = 8
        private const val DEFAULT_MAX_TOTAL_BYTES = 256L * 1024 // 256 KiB
    }
}
