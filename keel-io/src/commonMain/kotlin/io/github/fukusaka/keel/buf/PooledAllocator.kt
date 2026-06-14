@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

/**
 * Common pool-based [BufferAllocator] skeleton shared by the platform pools
 * ([SlabAllocator] on Native, [PooledDirectAllocator] on JVM).
 *
 * Holds the cross-platform machinery — the [SizeClasses] table, the size-class
 * indexed freelists, the per-class budget, [registerPoolSize] /
 * [installDefaultLadder], [createChild] propagation, and the allocate /
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
 *   is forwarded to every per-EventLoop child produced by [createChild].
 */
abstract class PooledAllocator(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    /**
     * Exposed `protected` so subclasses can forward the same factory to
     * per-EventLoop children produced by [newChildInstance]. Treat as read-only.
     */
    protected val freelistFactory: FreelistFactory? = null,
    /**
     * Optional opt-in instrumentation: when non-`null`, every [allocate] dispatch
     * records the path it took (pool hit / miss / empty / huge) into this profile
     * — see [PoolMissProfile] for the taxonomy. Off by default; only wire when
     * profiling (e.g. a benchmark `--profile-alloc` flag). The shared profile is
     * thread-safe and forwarded to per-EventLoop children produced by [createChild]
     * so all EventLoops aggregate into one histogram. Each recorded path adds a
     * single atomic increment to the otherwise hot allocate path.
     */
    val missProfile: PoolMissProfile? = null,
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

    /**
     * Closed state for the lifecycle contract. Written once on [close] and
     * read from [allocate] / [createChild] / [returnToPool] so:
     *
     * - post-close [allocate] / [createChild] fail fast with
     *   `IllegalStateException`.
     * - post-close [returnToPool] (driven by a buffer that was in use at
     *   close and is now being released) frees the backing directly
     *   instead of pushing to an already-drained freelist.
     *
     * `@Volatile` so a release on a thread other than the allocator's
     * owning EventLoop sees the flip without crossing a synchronisation
     * primitive on the hot path.
     */
    @kotlin.concurrent.Volatile
    private var closed: Boolean = false

    /**
     * Per-EventLoop children produced by [createChild]. The parent's
     * [close] propagates to each child first so the close direction matches
     * the construction direction (engine → group → per-EL allocators →
     * engine teardown closes parent which fans back out). Mutated only at
     * EventLoop construction (`newChildInstance` invocations) and at teardown
     * (`close`) — both single-threaded by contract — so a plain
     * `MutableList` suffices.
     */
    private val children: MutableList<PooledAllocator> = mutableListOf()

    // Cache-trim bookkeeping (per-EventLoop, single-thread — same writer contract as
    // registerPoolSize). Kept off the COW Ladder because they mutate on the hot path.
    /** Cached entries per size class (push ++ / pop --); exposed for test/diagnostics. */
    private val cachedCount = IntArray(sizeClasses.nSizes)

    /**
     * Cache hits per size class since the last trim — Netty's per-cache `allocations`
     * counter. The trim budget keeps `slotCap - this` (see [trim]).
     */
    private val allocsSinceTrim = IntArray(sizeClasses.nSizes)

    /** Allocations remaining until the next trim pass. */
    private var trimCountdown = TRIM_INTERVAL

    private val poolOwner: IoBufOwner = PoolOwner { buf -> returnToPool(buf) }

    /**
     * Per-EventLoop chunk back-end. A cache miss for a pooled class carves a
     * run/subpage view out of a large chunk instead of a per-buffer system
     * allocation. The size-class freelist sits in front, so [ChunkArena.carve]
     * only runs on misses (off the hot path). Each allocator instance — and each
     * per-EventLoop child from [newChildInstance] — owns its own arena.
     */
    private val chunkArena: ChunkArena = ChunkArena(
        sizeClasses = sizeClasses,
        newChunkBacking = { newBuffer(CHUNK_SIZE) },
        newChunkView = ::newChunkView,
        owningAllocator = this,
    )

    /**
     * Single arena-level lock guarding every mutation of [chunkArena] / [PooledChunk]
     * state (chunks list, run / subpage carve, run / subpage release, chunk reclaim).
     * Option A topology: all paths through the chunk back-end serialise on this
     * one mutex. The per-size-class [Freelist] in front of [chunkArena] supplies
     * its own concurrency for the hot allocate / release path; this lock only
     * fires on the freelist-miss carve path and on the release path that returns
     * a run / subpage to its chunk.
     *
     * Held by:
     * - the freelist-miss carve path in [allocate] (around `chunkArena.carve`).
     * - the freelist-miss release path in [returnToPool] / [trim] (around
     *   `freeBacking` → [PooledChunk.freeRun] via the `owningAllocator` back
     *   pointer's [withArenaLock]).
     * - the cache-trim pass in [trim] (around `chunkArena.reclaim`).
     *
     * Destroyed in [close] **after** [chunkArena.close] so any straggler release
     * from an in-flight buffer that survives until close still finds a live
     * mutex; the post-close branch ([isClosed]) routes those releases to the
     * unsynchronised path because the close contract forbids concurrent
     * allocate at that point.
     */
    private val arenaLock: PlatformLock = PlatformLock()

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
    protected abstract fun newChildInstance(maxTotalBytes: Long): PooledAllocator

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
        check(!closed) { "allocator is closed" }
        // Preserve the empty-buffer marker semantics: allocate(0) yields a true
        // zero-capacity buffer rather than rounding up to the smallest class.
        if (capacity == 0) {
            missProfile?.recordEmpty()
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
                    missProfile?.recordHit(idx)
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
                missProfile?.recordMiss(idx)
                val fresh = arenaLock.withLock { chunkArena.carve(idx) }
                (fresh as AbstractIoBuf).owner = poolOwner
                maybeTrim()
                return fresh
            }
        }
        // Above the cache cap or above the whole ladder (huge): exact, unpooled.
        missProfile?.recordHuge()
        val fresh = newBuffer(capacity)
        (fresh as AbstractIoBuf).owner = poolOwner
        return fresh
    }

    private fun returnToPool(buf: IoBuf) {
        if (closed) {
            // Allocator was closed while this buffer was in use; the freelist
            // is already drained and any Freelist OS resources are released,
            // so go straight to freeBacking instead of pushing into a closed
            // pool. Chunk-backed views return their run to the chunk; the
            // chunk's own refCount drops once all its views are released and
            // frees its backing.
            (buf as AbstractIoBuf).freeBacking()
            return
        }
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
     * Cache-trim pass: for each size class, evict the entries its recent activity
     * does not justify keeping, returning their runs to their chunks, then reclaim
     * now-idle chunks (keeping [WARM_RESERVE]).
     *
     * The per-class budget is Netty `PoolThreadCache.MemoryRegionCache.trim`'s
     * formula verbatim: `free = capacity - allocationsSinceTrim` (here
     * `slotCap[idx] - allocsSinceTrim[idx]`), then poll up to `free` entries until
     * the freelist is empty. A hot class (hits ≥ its slot capacity) keeps every
     * entry; a cold class (few/no hits) drains toward empty. Keying the budget on
     * the *capacity* — not the current cached count — is what matches Netty: a
     * partially-filled cold cache is drained fully rather than retained.
     *
     * Without this, cached views pin their chunks forever and the footprint only
     * grows.
     */
    private fun trim() {
        trimCountdown = TRIM_INTERVAL
        val l = ladder
        for (idx in 0 until sizeClasses.nSizes) {
            val pool = l.pools[idx] ?: continue
            // Netty MemoryRegionCache.trim: free = size (capacity) - allocations.
            var evict = l.slotCap[idx] - allocsSinceTrim[idx]
            allocsSinceTrim[idx] = 0
            while (evict > 0) {
                val buf = pool.pop() ?: break // freelist empty → stop, like Netty's free()
                cachedCount[idx]--
                (buf as AbstractIoBuf).freeBacking() // chunk-carved view → returns its run
                evict--
            }
        }
        arenaLock.withLock { chunkArena.reclaim(WARM_RESERVE) }
    }

    /**
     * Whether [close] has run. Exposed `internal` so [PooledChunk]'s release
     * path can detect a post-close release and skip lock acquisition — the
     * close contract guarantees no concurrent allocate at that point, so the
     * unsynchronised teardown is safe.
     */
    internal val isClosed: Boolean get() = closed

    /**
     * Runs [block] under the arena lock. Exposed `internal` so the
     * [PooledChunk] release path takes the same lock as the allocate miss
     * path and the trim pass.
     */
    internal inline fun <T> withArenaLock(block: () -> T): T = arenaLock.withLock(block)

    final override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        sliceDefaultIoBuf(source, offset, length)

    final override fun createChild(): BufferAllocator {
        check(!closed) { "allocator is closed" }
        val child = newChildInstance(maxTotalBytes)
        children.add(child)
        return child
    }

    /**
     * Closes this allocator and every child produced by
     * [createChild]. Idempotent — a second call is a no-op.
     *
     * Order: children are closed first (matching construction direction),
     * then this instance's freelists are drained (each pooled buffer's
     * `freeBacking` is invoked), each [Freelist.close] runs (releasing any
     * OS resource such as a `pthread_mutex_t`), and the chunk arena drops
     * its own references to every chunk. Chunks with no live views free
     * their backing immediately; chunks still referenced by an in-flight
     * buffer survive until the last view is released — those releases go
     * through the closed-flag branch in [returnToPool] and free directly.
     *
     * The contract guarantees a single-threaded teardown: engines stop
     * their EventLoop threads before invoking [close], so there are no
     * concurrent [allocate] calls. Implementations may still observe a
     * post-close [returnToPool] from a buffer whose owner held it across
     * the close (e.g. an in-flight write that has not yet flushed); that
     * path is handled by the closed-flag branch in [returnToPool].
     */
    final override fun close() {
        if (closed) return
        closed = true
        for (i in children.indices) children[i].close()
        children.clear()
        val l = ladder
        for (i in l.pools.indices) {
            val pool = l.pools[i] ?: continue
            while (true) {
                val buf = pool.pop() ?: break
                cachedCount[i]--
                (buf as AbstractIoBuf).freeBacking()
            }
            pool.close()
        }
        chunkArena.close()
        // Release the arena mutex this allocator owns. Idempotent at the
        // PlatformLock level. Done after chunkArena.close() so any chunk-backed
        // view that gets released between the freelist drain above and this
        // line still finds a live lock; releases land in the closed-flag
        // branch of returnToPool which freeBacking()s directly, but
        // freeBacking() can still call into PooledChunk.freeRun which routes
        // through the isClosed branch to skip the lock acquire on the way out.
        arenaLock.close()
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
