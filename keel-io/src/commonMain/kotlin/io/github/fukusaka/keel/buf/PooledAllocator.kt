@file:OptIn(UnsafeIoBufApi::class, kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

/**
 * Common pool-based [BufferAllocator] skeleton shared by the platform pools
 * ([SlabAllocator] on Native, [PooledDirectAllocator] on JVM).
 *
 * Holds the cross-platform machinery — the [SizeClasses] table, the size-class
 * indexed freelists, the per-class budget, [hintSizeClass] /
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
 * `@Volatile` reference). [hintSizeClass] / [installDefaultLadder] are
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
abstract class PooledAllocator internal constructor(
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    /**
     * Exposed `protected` so subclasses can forward the same factory to
     * per-EventLoop children produced by [newChildInstance]. Treat as read-only.
     */
    protected val freelistFactory: FreelistFactory? = null,
    /**
     * Optional observer that receives every [allocate] / [returnToPool] event as a
     * primitive-arg + enum-arg [BufferAllocatorStatsCounter.onAllocate] /
     * [BufferAllocatorStatsCounter.onRelease] call. Defaults to [NoOpStatsCounter]
     * so the hot path stays branch-free (monomorphic dispatch on the singleton
     * inlines / elides). The same counter instance is forwarded to per-EventLoop
     * children produced by [createChild] so all EventLoops aggregate into one
     * counter — pass a thread-safe implementation when multi-EL aggregation is
     * required. [PoolMissProfile] satisfies this interface, so existing
     * `--profile-alloc` wiring continues to work.
     */
    val statsCounter: BufferAllocatorStatsCounter = NoOpStatsCounter,
    /**
     * Optional identity-bearing listener that receives every [allocate] /
     * release event with the produced [IoBuf] reference. Defaults to
     * [NoOpLifecycleListener] so the hot path stays branch-free. Used for
     * per-buffer leak detection and lifecycle audits — the metric channel is
     * the cheaper [statsCounter]; install a listener here only when the
     * caller needs identity (e.g. [TrackingAllocator] / [LeakDetectingAllocator]
     * for engine-direct `IoBuf` coverage). Forwarded to per-EventLoop children
     * produced by [createChild] so all EventLoops feed one listener — pass a
     * thread-safe implementation when multi-EL aggregation is required.
     */
    override val lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
    /**
     * The sharded chunk back-end this allocator carves pool-miss buffers from.
     * `null` (the default, for a root allocator) makes this instance create and
     * **own** a fresh [ShardedChunkArena]; a non-null value (passed by [createChild]
     * to a per-EventLoop child) makes the child **share** the parent's sharded arena.
     * The shared arena is the off-EventLoop-safe central back-end: every child carves
     * from and returns runs to one [ShardedChunkArena], whose per-shard [ArenaLock]s
     * serialise cross-thread access while spreading it across shards. Only the owner
     * closes the arena (see [close]); a child closing a shared arena while siblings
     * still use it would be a use-after-free.
     */
    sharedArena: ShardedChunkArena? = null,
    /**
     * The shard this instance carves from by default (its pinned shard, Netty
     * `leastUsedArena` style: ~one EventLoop per shard). Assigned by [createChild]
     * (the child's index); [shardIndexForCarve] may override per-carve (the Native
     * allocator hashes off-EL threads across shards). The root's value goes almost
     * unused where engines are concerned, because they read through children —
     * the exceptions being the single-buffer check two of them make while being
     * built, and the in-memory test engine, which copies through a root on every
     * flush. A caller allocating from a root directly uses it like any other
     * instance.
     */
    protected val shardIdx: Int = 0,
    /**
     * The number of central [ShardedChunkArena] shards this instance creates when
     * it owns the arena (a root allocator). Defaults to [defaultShardCount] (the
     * core count). Pass a custom value — e.g. to match a non-default
     * `config.threads` EventLoop count — so each EventLoop still pins to its own
     * shard; [normalizeShardCount] rounds it up to a power of two and caps it.
     * Ignored when [sharedArena] is non-null (a child shares the root's arena).
     */
    shardCount: Int = defaultShardCount(),
) : BufferAllocator {

    /** The size-class table driving round-up. Built once with keel's pooling parameters. */
    private val sizeClasses: SizeClasses = SizeClasses(PAGE_SIZE, PAGE_SHIFTS, CHUNK_SIZE, NO_ALIGNMENT)

    /**
     * Pre-computed [SizeTier] for each size-class index. Resolved once at init so the
     * hot-path [allocate] / [returnToPool] emit paths read the tier via an indexed
     * lookup instead of recomputing `SizeTier.fromBytes(classSize)` on every event.
     */
    private val tierByClassIdx: Array<SizeTier> = Array(sizeClasses.nSizes) { idx ->
        SizeTier.fromBytes(sizeClasses.sizeIdx2size(idx))
    }

    /**
     * Snapshot of the constant size-class capacities, materialised once so
     * [stats] adapters can hold the same array reference for the allocator's
     * lifetime instead of rebuilding it per call.
     */
    private val sizeClassesArray: IntArray = IntArray(sizeClasses.nSizes) { idx ->
        sizeClasses.sizeIdx2size(idx)
    }

    // Internal cumulative counters backing [AllocatorStats.snapshot]. PooledAllocator
    // is EL-pinned for writes (the hot path runs on the owning EventLoop thread);
    // @Volatile ensures the collection-cycle reader on the OT thread sees eventual
    // consistency. Plain `++` on a Volatile Long is non-atomic and races with the
    // off-EL release path (cross-thread `IoBuf.release()` → `returnToPool`), matching
    // the per-class `allocsSinceTrim` / `trimCountdown` trim-heuristic convention —
    // a lost increment surfaces as a tiny discrepancy at the next snapshot, not as
    // state corruption.
    @kotlin.concurrent.Volatile
    private var cumulativeAllocations: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativeReleases: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativeAllocBytes: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativeReleaseBytes: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativeHits: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativeMisses: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativeEmpty: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativeHuge: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativePooled: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativeDiscarded: Long = 0L

    @kotlin.concurrent.Volatile
    private var cumulativeFreed: Long = 0L

    private val perClassAllocations: LongArray = LongArray(sizeClasses.nSizes)
    private val perClassReleases: LongArray = LongArray(sizeClasses.nSizes)
    private val perClassHits: LongArray = LongArray(sizeClasses.nSizes)
    private val perClassMisses: LongArray = LongArray(sizeClasses.nSizes)

    /**
     * Immutable ladder snapshot, replaced wholesale on [hintSizeClass] /
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

    // Cache-trim bookkeeping (per-EventLoop, single-thread on the common path — same
    // writer contract as hintSizeClass). Kept off the COW Ladder because they mutate
    // on the hot path.
    //
    // Approximate under concurrent allocate: a pooled channel consumed via
    // asSuspendSource from a non-EventLoop coroutine has the engine push read path
    // and the caller's pull refill both allocating on this allocator, so these plain
    // counters can race. The drift is benign — they only steer the *trim heuristic*
    // (cadence + per-class evict budget), which is approximate by design (Netty's
    // MemoryRegionCache.trim formula). The trim *operation* is fully safe: the
    // freelist pop is lock-guarded, carve/reclaim is ArenaLock-guarded, and the
    // cross-thread return queue is lock-free. Making them precise would cost an
    // atomic on every allocate (measured ~+22%, rejected) for no user-visible gain,
    // and trimCountdown is global so it cannot fold into a per-class freelist lock.
    // The exact cached count is *not* among these — it is read from [Freelist.size]
    // (consistent under the freelist's own lock), so diagnostics stay correct.

    /**
     * Cache hits per size class since the last trim — Netty's per-cache `allocations`
     * counter. The trim budget keeps `slotCap - this` (see [trim]). Approximate under
     * concurrent allocate (see the note above): benign trim-budget drift.
     */
    private val allocsSinceTrim = IntArray(sizeClasses.nSizes)

    /**
     * Allocations remaining until the next trim pass. Approximate under concurrent
     * allocate (see the note above): benign trim-cadence drift.
     */
    private var trimCountdown = TRIM_INTERVAL

    private val poolOwner: IoBufOwner = PoolOwner { buf -> returnToPool(buf) }

    /**
     * Sharded chunk back-end. A cache miss for a pooled class carves a run/subpage
     * view out of a large chunk instead of a per-buffer system allocation. The
     * size-class freelist sits in front, so a carve only runs on misses (off the hot
     * path), and [shardIndexForCarve] picks which shard.
     *
     * A root allocator (`sharedArena == null`) creates and owns a fresh
     * [ShardedChunkArena]; per-EventLoop children created by [createChild] **share**
     * it, so all EventLoops carve from and return runs to one off-EventLoop-safe
     * central back-end whose per-shard [ArenaLock]s serialise cross-thread access
     * while spreading it across shards. A buffer carved on one EventLoop and released
     * on another returns its run through its shard's lock. Only the owner closes it
     * ([ownsChunkArena]).
     */
    private val shardedArena: ShardedChunkArena = sharedArena ?: ShardedChunkArena(
        shardCount = normalizeShardCount(shardCount),
        sizeClasses = sizeClasses,
        newChunkBacking = { newBuffer(CHUNK_SIZE) },
        newChunkView = ::newChunkView,
    )

    /** True when this instance created its own [shardedArena] and must close it. */
    private val ownsChunkArena: Boolean = sharedArena == null

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
     * Subclasses must propagate the same [freelistFactory] / [statsCounter] /
     * [lifecycleListener] so per-EL children inherit the user-selected strategy and
     * feed one aggregate observer, and must pass [sharedArena] / [shardIdx] straight
     * to `super(... sharedArena = sharedArena, shardIdx = shardIdx)` so the child
     * shares the root's sharded central arena (instead of creating its own) and
     * carves from its assigned [shardIdx].
     */
    internal abstract fun newChildInstance(
        maxTotalBytes: Long,
        sharedArena: ShardedChunkArena,
        shardIdx: Int,
    ): PooledAllocator

    /**
     * The shard index this allocator's pool-miss carve routes to. The base default
     * is the instance's pinned [shardIdx] (Netty `leastUsedArena` style: one EL per
     * shard, uncontended). The Native allocator overrides this to hash off-EL threads
     * across shards (an EL thread on its own child → pinned; a foreign thread → its
     * thread id), so concurrent carves from many threads spread across shard locks.
     * The returned value is masked to the shard count by [ShardedChunkArena.carve],
     * so an override may return any int (e.g. a raw thread id).
     */
    protected open fun shardIndexForCarve(): Int = shardIdx

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

    final override fun hintSizeClass(byteSize: Int, maxCount: Int) {
        if (byteSize <= 0 || maxCount <= 0) return
        val idx = sizeClasses.size2SizeIdx(byteSize)
        if (idx >= sizeClasses.nSizes) return // huge: not poolable
        val classSize = sizeClasses.sizeIdx2size(idx)
        if (classSize > MAX_CACHED_CAPACITY) return // above the cache cap: unpooled
        val cur = ladder
        if (cur.pools[idx] != null) return // class already pooled (no-op, matches prior duplicate behaviour)
        var cap = maxCount
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
        // Record the owning (allocating) thread on the first allocation so a later
        // release can tell same-thread from cross-thread. A no-op past the first
        // touch and on allocators that do not implement cross-thread routing.
        captureOwnerThread()
        // Preserve the empty-buffer marker semantics: allocate(0) yields a true
        // zero-capacity buffer rather than rounding up to the smallest class.
        if (capacity == 0) {
            val empty = newBuffer(0)
            (empty as AbstractIoBuf).owner = poolOwner
            recordAllocate(
                buf = empty,
                byteSize = 0,
                classIdx = EMPTY_CLASS_IDX,
                tier = SizeTier.TINY,
                path = AllocPath.EMPTY,
            )
            return empty
        }
        val l = ladder
        val idx = sizeClasses.size2SizeIdx(capacity)
        if (idx < sizeClasses.nSizes) {
            val classSize = sizeClasses.sizeIdx2size(idx)
            if (classSize <= MAX_CACHED_CAPACITY) {
                val pool = l.pools[idx]
                var recycled = pool?.pop()
                if (recycled == null && beforePoolMiss(idx)) {
                    // The Native sharded allocator drained its cross-thread return
                    // queue into this class's pool; retry the pop so a returned
                    // buffer is reused instead of carving a fresh run (re-carve
                    // avoidance). On the same-thread fast path the hook is a no-op
                    // that returns false, so this branch is never entered there.
                    recycled = pool?.pop()
                }
                if (recycled != null) {
                    allocsSinceTrim[idx]++ // working-set signal: this class served from cache
                    (recycled as AbstractIoBuf).resetForReuse()
                    recycled.owner = poolOwner
                    recordAllocate(
                        buf = recycled,
                        byteSize = capacity,
                        classIdx = idx,
                        tier = tierByClassIdx[idx],
                        path = AllocPath.HIT,
                    )
                    maybeTrim()
                    return recycled
                }
                // Pool miss: carve a class-sized view from a chunk (not a per-buffer
                // system allocation). The view's capacity is the class size, so it
                // pools on release like any cached buffer; its freeBacking returns
                // the run to the chunk when the pool is full.
                val fresh = shardedArena.carve(idx, shardIndexForCarve())
                (fresh as AbstractIoBuf).owner = poolOwner
                recordAllocate(
                    buf = fresh,
                    byteSize = capacity,
                    classIdx = idx,
                    tier = tierByClassIdx[idx],
                    path = AllocPath.MISS,
                )
                maybeTrim()
                return fresh
            }
        }
        // Above the cache cap or above the whole ladder (huge): exact, unpooled.
        val fresh = newBuffer(capacity)
        (fresh as AbstractIoBuf).owner = poolOwner
        recordAllocate(
            buf = fresh,
            byteSize = capacity,
            classIdx = HUGE_CLASS_IDX,
            tier = SizeTier.HUGE,
            path = AllocPath.HUGE,
        )
        return fresh
    }

    /**
     * Returns [buf] to its size-class pool (or frees its backing). Open so the
     * Native sharded-return allocator can intercept a *cross-thread* release and
     * route it through a lock-free MPSC queue instead of contending on this
     * EventLoop's freelist; the default forwards straight to [returnToPoolLocal]
     * (the same-thread fast path). This is the seam `PoolOwner` invokes.
     */
    protected open fun returnToPool(buf: IoBuf) {
        returnToPoolLocal(buf)
    }

    /**
     * The return body: push to the class freelist, or free the backing on
     * slot-cap / no-pool / closed.
     *
     * Safe under concurrent callers. The common path is the owning EventLoop thread
     * (directly, or via the MPSC drain), but a pooled channel consumed via
     * `asSuspendSource` from a non-EventLoop coroutine can have the engine push path
     * and the caller's pull refill both reach here at once. That is fine: the
     * freelist [Freelist.push] is itself thread-safe, the backing free is per-buffer
     * (and `ChunkArena.returnRun` is ArenaLock-guarded), and the only shared state it
     * mutates is the cumulative stat counters whose plain `++` is the documented
     * lossy-snapshot convention (see the counter note above). The exact cached count
     * is read from [Freelist.size], not a counter here, so it stays consistent.
     */
    protected fun returnToPoolLocal(buf: IoBuf) {
        val cap = buf.capacity
        val idx = resolveClassIdx(cap)
        if (!closed && idx >= 0) {
            val pool = ladder.pools[idx]
            if (pool != null) {
                if (pool.push(buf)) {
                    recordRelease(
                        buf = buf,
                        byteSize = cap,
                        classIdx = idx,
                        tier = tierByClassIdx[idx],
                        outcome = ReleaseOutcome.POOLED,
                    )
                    return
                }
                // Pool slot cap reached: drop the buffer's backing instead of pushing.
                recordRelease(
                    buf = buf,
                    byteSize = cap,
                    classIdx = idx,
                    tier = tierByClassIdx[idx],
                    outcome = ReleaseOutcome.DISCARDED,
                )
                (buf as AbstractIoBuf).freeBacking()
                return
            }
        }
        // No class for this size, allocator closed, or no pool installed for this
        // class: free the backing directly. refCount is already zero (we are inside
        // PoolOwner.release). Chunk-backed views return their run to the chunk; the
        // chunk's own refCount drops once all its views are released and frees its
        // backing.
        val tier = if (idx >= 0) tierByClassIdx[idx] else SizeTier.fromBytes(cap)
        recordRelease(
            buf = buf,
            byteSize = cap,
            classIdx = idx,
            tier = tier,
            outcome = ReleaseOutcome.FREED,
        )
        (buf as AbstractIoBuf).freeBacking()
    }

    /**
     * Records the allocating thread's identity. Called on every [allocate] but
     * meant to capture once on first touch (the Native sharded allocator
     * implements this; the default does nothing). Must stay cheap — it runs on the
     * allocate hot path.
     */
    protected open fun captureOwnerThread() {}

    /**
     * Pool-miss hook: gives the Native sharded allocator a chance to drain its
     * cross-thread return queue back into the per-class pools before [allocate]
     * carves a fresh run. Returns `true` if a drain ran (so [allocate] retries the
     * pop, avoiding a needless carve); the default does nothing and returns
     * `false`, so the fast path skips the retry entirely.
     */
    protected open fun beforePoolMiss(idx: Int): Boolean = false

    /**
     * Trim hook: lets the Native sharded allocator drain its cross-thread return
     * queue so the [trim] pass evaluates every cached buffer. The default does
     * nothing.
     */
    protected open fun beforeTrim() {}

    /**
     * Close hook: lets the Native sharded allocator drain its cross-thread return
     * queue and free those buffers' backing during [close]. Runs after the closed
     * flag is set. The default does nothing.
     */
    protected open fun onClose() {}

    /**
     * Whether [close] has run. The Native sharded allocator reads this so a
     * cross-thread release arriving after close frees the backing directly instead
     * of enqueuing into a return queue the stopped owner will never drain.
     */
    protected val isClosed: Boolean get() = closed

    /**
     * Updates the internal counters backing [AllocatorStats.snapshot] and forwards
     * the event to the user-supplied [statsCounter]. Single emit point so
     * `onAllocate` callers and the per-class accumulation stay in step; the user
     * callback fires after the internal counters move so a callback that itself
     * calls back into `stats()` observes the same number it just saw. The
     * identity-bearing [lifecycleListener] also fires here so per-buffer
     * tracking shares the same emit point as the metric channel.
     */
    private fun recordAllocate(buf: IoBuf, byteSize: Int, classIdx: Int, tier: SizeTier, path: AllocPath) {
        cumulativeAllocations++
        cumulativeAllocBytes += byteSize.toLong()
        when (path) {
            AllocPath.HIT -> {
                cumulativeHits++
                if (classIdx >= 0) {
                    perClassHits[classIdx]++
                    perClassAllocations[classIdx]++
                }
            }
            AllocPath.MISS -> {
                cumulativeMisses++
                if (classIdx >= 0) {
                    perClassMisses[classIdx]++
                    perClassAllocations[classIdx]++
                }
            }
            AllocPath.EMPTY -> cumulativeEmpty++
            AllocPath.HUGE -> cumulativeHuge++
        }
        statsCounter.onAllocate(byteSize, classIdx, tier, path, weight = 1)
        lifecycleListener.onAllocated(buf)
    }

    /** See [recordAllocate]. Counters for the release side. */
    private fun recordRelease(buf: IoBuf, byteSize: Int, classIdx: Int, tier: SizeTier, outcome: ReleaseOutcome) {
        cumulativeReleases++
        cumulativeReleaseBytes += byteSize.toLong()
        when (outcome) {
            ReleaseOutcome.POOLED -> cumulativePooled++
            ReleaseOutcome.DISCARDED -> cumulativeDiscarded++
            ReleaseOutcome.FREED -> cumulativeFreed++
        }
        if (classIdx >= 0) perClassReleases[classIdx]++
        statsCounter.onRelease(classIdx, tier, outcome, weight = 1)
        lifecycleListener.onReleased(buf)
    }

    /**
     * Captures all counter and pool-state fields into an immutable
     * [AllocatorStatsSnapshot]. Non-atomic reads — the OT collection cycle
     * tolerates the small per-field skew that may appear if a concurrent release
     * runs between the snapshot's `cumulativeAllocations` and `cumulativeReleases`
     * reads.
     */
    private fun buildSnapshot(): AllocatorStatsSnapshot {
        val nSizes = sizeClasses.nSizes
        return AllocatorStatsSnapshot(
            cumulativeAllocations = cumulativeAllocations,
            cumulativeReleases = cumulativeReleases,
            cumulativeAllocBytes = cumulativeAllocBytes,
            cumulativeReleaseBytes = cumulativeReleaseBytes,
            cumulativeHits = cumulativeHits,
            cumulativeMisses = cumulativeMisses,
            cumulativeEmpty = cumulativeEmpty,
            cumulativeHuge = cumulativeHuge,
            cumulativePooled = cumulativePooled,
            cumulativeDiscarded = cumulativeDiscarded,
            cumulativeFreed = cumulativeFreed,
            // Chunk metrics are arena-scoped. Only the arena owner (the root
            // allocator) reports them; per-EventLoop children share that one arena,
            // so reporting from every child would multiply the chunk counts by the
            // child count for any observer that sums per-child snapshots.
            cumulativeChunksAllocated = if (ownsChunkArena) shardedArena.cumulativeChunksAllocated else 0L,
            cumulativeChunksFreed = if (ownsChunkArena) shardedArena.cumulativeChunksFreed else 0L,
            residentChunks = if (ownsChunkArena) shardedArena.chunkCount else 0,
            isClosed = closed,
            classCount = nSizes,
            perClassHits = perClassHits.copyOf(),
            perClassMisses = perClassMisses.copyOf(),
            perClassAllocations = perClassAllocations.copyOf(),
            perClassReleases = perClassReleases.copyOf(),
            // Derived from each freelist's own (lock-consistent) count rather than a
            // separate counter, so it is correct even under concurrent allocate.
            perClassCachedCount = IntArray(nSizes) { ladder.pools[it]?.size() ?: 0 },
            perClassSizes = sizeClassesArray.copyOf(),
            perClassTiers = tierByClassIdx.copyOf(),
        )
    }

    /**
     * View object wrapping this allocator as an [AllocatorStats] handle. The
     * constant-config fields are pre-allocated; [snapshot] captures the dynamic
     * counters on every call.
     */
    private val statsView: AllocatorStats = object : AllocatorStats {
        override val poolName: String = this@PooledAllocator::class.simpleName ?: "PooledAllocator"
        override val sizeClasses: IntArray get() = this@PooledAllocator.sizeClassesArray.copyOf()
        override val slotCaps: IntArray get() = this@PooledAllocator.ladder.slotCap.copyOf()
        override val chunkSize: Int = CHUNK_SIZE
        override fun snapshot(): AllocatorStatsSnapshot = buildSnapshot()
    }

    final override fun stats(): AllocatorStats = statsView

    /**
     * Maps a buffer capacity back to its size-class index, or returns [HUGE_CLASS_IDX]
     * when the capacity falls outside the cache range. Used by [returnToPool] to tag
     * release events with the correct `classIdx`.
     */
    private fun resolveClassIdx(cap: Int): Int {
        if (cap !in 1..MAX_CACHED_CAPACITY) return HUGE_CLASS_IDX
        val idx = sizeClasses.size2SizeIdx(cap)
        return if (idx < sizeClasses.nSizes && sizeClasses.sizeIdx2size(idx) == cap) idx else HUGE_CLASS_IDX
    }

    /** Runs a [trim] pass once every [TRIM_INTERVAL] allocations of a cached class. */
    private fun maybeTrim() {
        if (--trimCountdown <= 0) trim()
    }

    /** Resident chunk count (test/diagnostic observability). */
    internal val chunkCount: Int get() = shardedArena.chunkCount

    /** Central [ShardedChunkArena] shard count, post-normalisation (test/diagnostic observability). */
    internal val centralShardCount: Int get() = shardedArena.shardCount

    /** Cached entry count for [capacity]'s size class (test/diagnostic observability). */
    internal fun cachedCountOf(capacity: Int): Int =
        ladder.pools[sizeClasses.size2SizeIdx(capacity)]?.size() ?: 0

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
     *
     * **Shared arena**: [WARM_RESERVE] is a single arena-wide idle-chunk reserve,
     * not a per-EventLoop one. When [createChild] shares one [ChunkArena] across
     * children, every child's trim pass reclaims that same shared arena down to the
     * one global reserve, so the resident idle-chunk floor is [WARM_RESERVE] total
     * (not × the child count). Reclaim is idempotent and runs under the arena lock,
     * so concurrent per-child trims are serialised rather than racing.
     */
    private fun trim() {
        // Drain any cross-thread returns first (Native sharded allocator) so this
        // pass evaluates the full cached set before deciding what to evict.
        beforeTrim()
        trimCountdown = TRIM_INTERVAL
        val l = ladder
        for (idx in 0 until sizeClasses.nSizes) {
            val pool = l.pools[idx] ?: continue
            // Netty MemoryRegionCache.trim: free = size (capacity) - allocations.
            var evict = l.slotCap[idx] - allocsSinceTrim[idx]
            allocsSinceTrim[idx] = 0
            while (evict > 0) {
                val buf = pool.pop() ?: break // freelist empty → stop, like Netty's free()
                (buf as AbstractIoBuf).freeBacking() // chunk-carved view → returns its run
                evict--
            }
        }
        shardedArena.reclaim(shardIdx, WARM_RESERVE)
    }

    final override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        sliceDefaultIoBuf(source, offset, length)

    /**
     * Monotonic shard-assignment counter for [createUntrackedChild]. Tracked
     * children pin to `children.size` (one EventLoop per shard); untracked
     * children are an unbounded, churning population (one per connection), so
     * they round-robin across shards via this counter instead — otherwise every
     * untracked child would pin to the same shard and serialise its owner-context
     * carves on one shard lock. Masked to the shard count by [ShardedChunkArena].
     */
    private val untrackedChildShard = kotlin.concurrent.atomics.AtomicInt(0)

    final override fun createChild(): BufferAllocator = newChild(track = true)

    final override fun createUntrackedChild(): BufferAllocator = newChild(track = false)

    /**
     * Shared construction for [createChild] (tracked) and [createUntrackedChild]
     * (untracked). Both share this allocator's sharded chunk arena so every child
     * carves from and returns runs to one off-EventLoop-safe central back-end; the
     * child owns its own size-class freelist cache (per-owner, lock-free hot path)
     * but borrows the shared arena for misses, pinned to one shard.
     *
     * When [track] is `true` the child joins [children] and this parent's [close]
     * cascade-closes it, pinned to `children.size` (Netty leastUsedArena style:
     * ~one EventLoop per shard). When `false` the child is left out of [children]
     * — the caller owns its [close] — and its shard is round-robin from
     * [untrackedChildShard] so an unbounded connection population spreads across
     * shards. The untracked path never touches [children], so it stays safe under
     * the concurrent per-connection construction NWConnection drives.
     */
    private fun newChild(track: Boolean): BufferAllocator {
        check(!closed) { "allocator is closed" }
        val shard = if (track) children.size else untrackedChildShard.fetchAndAdd(1)
        val child = newChildInstance(maxTotalBytes, shardedArena, shard)
        if (track) children.add(child)
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
        // Let a sharded allocator drain its cross-thread return queue and free those
        // buffers' backing now: the owner EventLoop is stopped and will never drain
        // them otherwise. Runs after the closed flag is set, so any later
        // cross-thread release observes `closed` and frees directly in returnToPool
        // instead of enqueuing into a queue nobody drains.
        onClose()
        for (i in children.indices) children[i].close()
        children.clear()
        val l = ladder
        for (i in l.pools.indices) {
            val pool = l.pools[i] ?: continue
            while (true) {
                val buf = pool.pop() ?: break
                (buf as AbstractIoBuf).freeBacking()
            }
            pool.close()
        }
        // Only the root allocator closes the shared arena — a child closing it
        // while sibling EventLoops still carve from it would be a use-after-free.
        // The root's close() runs after every child's close() (children are closed
        // first, above), so by the time the owner tears the arena down, no child
        // freelist will issue a fresh carve; in-flight releases held across close
        // take the arena's closed-flag direct path (see ChunkArena.returnRun).
        if (ownsChunkArena) shardedArena.close()
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

        /**
         * Sentinel `classIdx` reported to [BufferAllocatorStatsCounter] for an
         * `allocate(0)` empty-marker path that does not belong to any size class.
         */
        private const val EMPTY_CLASS_IDX = -1

        /**
         * Sentinel `classIdx` reported to [BufferAllocatorStatsCounter] for the
         * huge-allocation path (above [MAX_CACHED_CAPACITY]) or any release whose
         * capacity does not match a registered size class.
         */
        private const val HUGE_CLASS_IDX = -1
    }
}
