@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

/**
 * Common pool-based [BufferAllocator] skeleton shared by the platform pools
 * ([SlabAllocator] on Native, [PooledDirectAllocator] on JVM).
 *
 * Holds the cross-platform machinery — the [SizeClasses] table, the size-class
 * indexed freelists, the per-class budget, [registerPoolSize] /
 * [installDefaultLadder], [createForEventLoop] propagation, and the allocate /
 * return-to-pool routing — while delegating the two platform- and
 * strategy-specific seams to subclasses:
 *
 * - [newBuffer] constructs a fresh backing buffer (`NativeIoBuf` / `DirectIoBuf`).
 * - [newFreelist] constructs the per-class [Freelist] (the pluggable concurrency
 *   strategy: spin lock / Treiber / mutex / versioned-index — see [Freelist]).
 *
 * **Size-class re-keying.** Instead of pooling only buffers whose capacity
 * *exactly* matches a registered size (the old fixed-8 KiB limitation), an
 * [allocate] request is rounded **up** to the smallest [SizeClasses] class that
 * can hold it and served from that class's freelist. A pool miss allocates a
 * fresh buffer *at the class size* so that, when released, it lands back in the
 * same class. This is the jemalloc / Netty answer (16 B quantum, 4 classes per
 * doubling, ~20–25 % worst-case internal fragmentation): any requested size
 * becomes poolable, not just exactly-registered ones.
 *
 * The returned buffer's capacity is the class size (≥ the request); this is
 * contract-compliant since [BufferAllocator.allocate] promises *at least*
 * `capacity` bytes, and the wire path writes `readableBytes`, never `capacity`.
 *
 * **Cached vs unpooled.** Classes up to [MAX_CACHED_CAPACITY] (Netty's
 * `DEFAULT_MAX_CACHED_BUFFER_CAPACITY` analogue) are pooled with a tiered slot
 * cap (smaller, hotter classes get more slots). Requests above it — or above the
 * whole ladder (`chunkSize`) — are allocated at their exact size and not pooled,
 * matching the existing large-buffer bypass. Freelists fill lazily: an unused
 * class costs only its (empty) freelist metadata.
 *
 * **Thread safety**: [allocate] / [returnToPool] are safe for concurrent callers
 * to the extent the chosen [Freelist] is (the ladder is read through a single
 * `@Volatile` reference). [registerPoolSize] / [installDefaultLadder] are
 * copy-on-write writers and must not run concurrently with themselves on the same
 * instance — they are invoked at construction and at per-EventLoop setup
 * (bind / TLS handler) on the owning thread, never on the hot path.
 *
 * @param maxTotalBytes Safety valve: an upper bound on the total bytes the cache
 *   may commit across all classes (worst case, every slot full). The default
 *   ladder's worst case fits within [DEFAULT_MAX_TOTAL_BYTES]; lazy fill keeps
 *   real residency far below it. (A runtime soft-cap with reclaim is a later
 *   roadmap phase; here it only clamps slot counts at install time.)
 * @param freelistFactory Optional override for the per-size-class [Freelist] strategy.
 *   When `null` (default), each subclass selects its own platform-tuned strategy
 *   (`SpinLockFreelist` on Native, intrusive Treiber on JVM). Pass a non-null
 *   [FreelistFactory] to swap in a different `Freelist` implementation — for
 *   example `::MutexFreelist` for an arbitrary-concurrency public allocator. The
 *   factory is invoked once per pooled size class with that class's slot cap, and
 *   is forwarded to every per-EventLoop child produced by [createForEventLoop].
 */
abstract class PooledAllocator(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    /**
     * Exposed `protected` so subclasses can forward the same factory to
     * per-EventLoop children produced by [createChild]. Treat as read-only.
     */
    protected val freelistFactory: FreelistFactory? = null,
) : BufferAllocator {

    /** The size-class table driving round-up. Built once with keel's pooling parameters. */
    private val sizeClasses: SizeClasses = SizeClasses(PAGE_SIZE, PAGE_SHIFTS, CHUNK_SIZE, NO_ALIGNMENT)

    /**
     * Immutable ladder snapshot, replaced wholesale on [registerPoolSize] /
     * [installDefaultLadder] (copy-on-write). [pools] is indexed by size-class
     * index; a `null` entry means that class is not pooled (uncached or not yet
     * installed). [committedBytes] is the worst-case byte budget the installed
     * classes commit (`Σ slotCap * classSize`).
     */
    private class Ladder(val pools: Array<Freelist?>, val slotCap: IntArray, val committedBytes: Long)

    @kotlin.concurrent.Volatile
    private var ladder: Ladder = Ladder(arrayOfNulls(sizeClasses.nSizes), IntArray(sizeClasses.nSizes), 0L)

    // Cache-trim bookkeeping (per-EventLoop, single-thread — same writer contract as
    // registerPoolSize). Kept off the COW Ladder because they mutate on the hot path.
    /** Cached entries per size class (push ++ / pop --), tracking each freelist's size. */
    private val cachedCount = IntArray(sizeClasses.nSizes)

    /** Cache hits per size class since the last trim — the working set the trim keeps. */
    private val allocsSinceTrim = IntArray(sizeClasses.nSizes)

    /** Allocations remaining until the next trim pass. */
    private var trimCountdown = TRIM_INTERVAL

    private val poolOwner: IoBufOwner = PoolOwner { buf -> returnToPool(buf) }

    /**
     * Per-EventLoop chunk back-end. A cache miss for a pooled class carves a
     * run/subpage view out of a large chunk instead of a per-buffer system
     * allocation. The size-class freelist sits in front, so [ChunkArena.carve]
     * only runs on misses (off the hot path). Each allocator instance — and each
     * per-EventLoop child from [createChild] — owns its own arena.
     */
    private val chunkArena: ChunkArena = ChunkArena(
        sizeClasses = sizeClasses,
        newChunkBacking = { newBuffer(CHUNK_SIZE) },
        newChunkView = ::newChunkView,
    )

    /** Constructs a fresh backing buffer of exactly [capacity] bytes (platform seam). */
    protected abstract fun newBuffer(capacity: Int): IoBuf

    /**
     * Builds a chunk-backed view (platform seam): a non-owning view over
     * [backing] at [byteOffset] of [length] bytes carrying the run-binding
     * `(pooledChunk, handle)`. Its `freeBacking` returns the run to the chunk
     * (see [ChunkBackedIoBuf]). Native = a pointer view; JVM = a `slice()`.
     */
    protected abstract fun newChunkView(
        backing: IoBuf,
        byteOffset: Int,
        length: Int,
        pooledChunk: PooledChunk,
        handle: Long,
    ): IoBuf

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

    /**
     * Installs the default Netty-style size-class ladder: every cached class
     * (size ≤ [MAX_CACHED_CAPACITY]) gets a freelist sized by [defaultSlotCap],
     * clamped so the total commitment stays within [maxTotalBytes]. Subclasses
     * call this once from their `init` block (after their own construction so the
     * [defaultFreelist] / [newFreelist] seam is ready).
     */
    protected fun installDefaultLadder() {
        val n = sizeClasses.nSizes
        val pools = arrayOfNulls<Freelist>(n)
        val caps = IntArray(n)
        var committed = 0L
        for (idx in 0 until n) {
            val classSize = sizeClasses.sizeIdx2size(idx)
            var cap = defaultSlotCap(classSize)
            if (cap <= 0) continue
            val cost = classSize.toLong() * cap
            if (committed + cost > maxTotalBytes) {
                cap = ((maxTotalBytes - committed) / classSize).toInt()
                if (cap <= 0) continue
            }
            caps[idx] = cap
            pools[idx] = newFreelist(cap)
            committed += classSize.toLong() * cap
        }
        ladder = Ladder(pools, caps, committed)
    }

    final override fun registerPoolSize(size: Int, maxSlots: Int) {
        if (size <= 0 || maxSlots <= 0) return
        val idx = sizeClasses.size2SizeIdx(size)
        if (idx >= sizeClasses.nSizes) return // huge: not poolable
        val classSize = sizeClasses.sizeIdx2size(idx)
        if (classSize > MAX_CACHED_CAPACITY) return // above the cache cap: unpooled
        val cur = ladder
        if (cur.pools[idx] != null) return // class already pooled (no-op, matches prior duplicate behaviour)
        var cap = maxSlots
        val budgetLeft = maxTotalBytes - cur.committedBytes
        if (classSize.toLong() * cap > budgetLeft) {
            cap = (budgetLeft / classSize).toInt()
            if (cap <= 0) return
        }
        val pools = cur.pools.copyOf()
        val caps = cur.slotCap.copyOf()
        pools[idx] = newFreelist(cap)
        caps[idx] = cap
        ladder = Ladder(pools, caps, cur.committedBytes + classSize.toLong() * cap)
    }

    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    final override fun allocate(capacity: Int): IoBuf {
        // Preserve the empty-buffer marker semantics: allocate(0) yields a true
        // zero-capacity buffer rather than rounding up to the smallest class.
        if (capacity == 0) {
            val empty = newBuffer(0)
            (empty as AbstractIoBuf).owner = poolOwner
            return empty
        }
        val l = ladder
        val idx = sizeClasses.size2SizeIdx(capacity)
        if (idx < sizeClasses.nSizes) {
            val classSize = sizeClasses.sizeIdx2size(idx)
            if (classSize <= MAX_CACHED_CAPACITY) {
                val pool = l.pools[idx]
                val recycled = pool?.pop()
                if (recycled != null) {
                    allocsSinceTrim[idx]++ // working-set signal: this class served from cache
                    cachedCount[idx]--
                    (recycled as AbstractIoBuf).resetForReuse()
                    recycled.owner = poolOwner
                    maybeTrim()
                    return recycled
                }
                // Pool miss: carve a class-sized view from a chunk (not a per-buffer
                // system allocation). The view's capacity is the class size, so it
                // pools on release like any cached buffer; its freeBacking returns
                // the run to the chunk when the pool is full.
                val fresh = chunkArena.carve(idx)
                (fresh as AbstractIoBuf).owner = poolOwner
                maybeTrim()
                return fresh
            }
        }
        // Above the cache cap or above the whole ladder (huge): exact, unpooled.
        val fresh = newBuffer(capacity)
        (fresh as AbstractIoBuf).owner = poolOwner
        return fresh
    }

    private fun returnToPool(buf: IoBuf) {
        val cap = buf.capacity
        if (cap in 1..MAX_CACHED_CAPACITY) {
            val l = ladder
            val idx = sizeClasses.size2SizeIdx(cap)
            // Only pool buffers whose capacity is exactly a class size (cache-path
            // buffers are); arbitrary-sized buffers from the unpooled path fall
            // through to freeBacking.
            if (idx < sizeClasses.nSizes && sizeClasses.sizeIdx2size(idx) == cap) {
                val pool = l.pools[idx]
                if (pool != null && pool.push(buf)) {
                    cachedCount[idx]++
                    return
                }
            }
        }
        // No class for this size, pool full, or above the cache cap: free the
        // backing directly. refCount is already zero (we are inside PoolOwner.release).
        (buf as AbstractIoBuf).freeBacking()
    }

    /** Runs a [trim] pass once every [TRIM_INTERVAL] allocations of a cached class. */
    private fun maybeTrim() {
        if (--trimCountdown <= 0) trim()
    }

    /** Resident chunk count (test/diagnostic observability). */
    internal val chunkCount: Int get() = chunkArena.chunkCount

    /** Cached entry count for [capacity]'s size class (test/diagnostic observability). */
    internal fun cachedCountOf(capacity: Int): Int = cachedCount[sizeClasses.size2SizeIdx(capacity)]

    /** Forces a [trim] pass immediately (test hook; production trims via [maybeTrim]). */
    internal fun trimNow() = trim()

    /**
     * Cache-trim pass: for each size class, evict the cached entries beyond its
     * recent working set (`cachedCount - hits since last trim`), returning their
     * runs to their chunks, then reclaim now-idle chunks (keeping [WARM_RESERVE]).
     * Hot classes keep their entries (hits ≥ cached → nothing evicted); cold
     * classes drain. Without this, cached views pin chunks forever and the
     * footprint never shrinks.
     */
    private fun trim() {
        trimCountdown = TRIM_INTERVAL
        val l = ladder
        for (idx in 0 until sizeClasses.nSizes) {
            val pool = l.pools[idx] ?: continue
            var evict = cachedCount[idx] - allocsSinceTrim[idx]
            allocsSinceTrim[idx] = 0
            while (evict > 0) {
                val buf = pool.pop() ?: break
                cachedCount[idx]--
                (buf as AbstractIoBuf).freeBacking() // chunk-carved view → returns its run
                evict--
            }
        }
        chunkArena.reclaim(WARM_RESERVE)
    }

    final override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        sliceDefaultIoBuf(source, offset, length)

    final override fun createForEventLoop(): BufferAllocator = createChild(maxTotalBytes)

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
        val l = ladder
        for (i in l.pools.indices) l.pools[i]?.snapshotInto(result)
        return result
    }

    /** Tiered per-class slot cap. Returns 0 for classes that are not cached. */
    private fun defaultSlotCap(classSize: Int): Int = when {
        classSize <= TINY_CLASS_MAX -> TINY_CLASS_SLOTS
        classSize <= PAGE_SIZE -> PAGE_CLASS_SLOTS
        classSize <= MAX_CACHED_CAPACITY -> LARGE_CLASS_SLOTS
        else -> 0
    }

    companion object {
        /** Pooling page size: the page-aligned read-buffer class (8 KiB). */
        const val PAGE_SIZE: Int = 8192

        /** `log2(PAGE_SIZE)`. */
        const val PAGE_SHIFTS: Int = 13

        /** Largest pooled size class; requests above this are huge / unpooled (256 KiB). */
        const val CHUNK_SIZE: Int = 256 * 1024

        /**
         * Largest size class that is cached. Classes above this (up to [CHUNK_SIZE])
         * are still valid round-up targets but are allocated fresh and not pooled —
         * the analogue of Netty's `DEFAULT_MAX_CACHED_BUFFER_CAPACITY` (32 KiB).
         */
        const val MAX_CACHED_CAPACITY: Int = 32 * 1024

        private const val NO_ALIGNMENT = 0

        // Tiered per-class slot caps (per-EventLoop). Smaller, hotter classes get
        // more slots; larger ones fewer. Initial values — to be re-measured against
        // an allocation profile at the chunk-back-end checkpoint.
        private const val TINY_CLASS_MAX = 512
        private const val TINY_CLASS_SLOTS = 16

        /** Default slot cap for the page tier (≤ [PAGE_SIZE]), including the 8 KiB read-buffer class. */
        internal const val PAGE_CLASS_SLOTS = 8
        private const val LARGE_CLASS_SLOTS = 4

        /**
         * Worst-case cache-byte safety valve (2 MiB). The default tiered ladder
         * commits ~1 MiB worst case; lazy fill keeps real residency far lower
         * (a typical HTTP EventLoop holds mostly the 8 KiB class).
         */
        internal const val DEFAULT_MAX_TOTAL_BYTES = 2L * 1024 * 1024

        /** Allocations between cache-trim passes (Netty's `freeSweepAllocationThreshold`). */
        internal const val TRIM_INTERVAL = 8192

        /** Idle chunks kept resident after a trim to avoid alloc/free thrashing. */
        internal const val WARM_RESERVE = 1
    }
}
