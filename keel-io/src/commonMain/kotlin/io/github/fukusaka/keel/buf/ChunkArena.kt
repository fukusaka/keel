package io.github.fukusaka.keel.buf

/**
 * Platform seam that builds a non-owning view over a chunk backing at a byte
 * offset, carrying the run-binding `(pooledChunk, handle)` — a `NativeIoBuf`
 * pointer view on Native, a `DirectIoBuf` `slice()` on JVM.
 */
internal typealias ChunkViewFactory =
    (backing: IoBuf, byteOffset: Int, length: Int, pooledChunk: PooledChunk, handle: Long) -> IoBuf

/**
 * The chunk back-end for a [PooledAllocator]: a list of [PooledChunk]s that
 * satisfy pool *misses* by carving a run/subpage view out of a large
 * pre-allocated chunk, instead of a `malloc` / `allocateDirect` per buffer.
 *
 * [carve] is invoked only on a cache miss (the per-size-class freelist sits in
 * front and absorbs the hot path), so the linear scan over chunks is off the hot
 * path. When no existing chunk can satisfy the request a fresh chunk is allocated
 * via [newChunkBacking]. The view itself is built by the platform [newChunkView]
 * seam (a `NativeIoBuf` pointer view / a `DirectIoBuf` slice) carrying the
 * `(PooledChunk, handle)` run-binding.
 *
 * **Lifecycle**: [carve] retains the chunk backing once per view; the view's
 * `freeBacking` (via [ChunkBackedIoBuf.returnChunkRun] → [returnRun]) releases it
 * and returns the run. Chunks are **not reclaimed** in this phase — they are held
 * for the allocator's lifetime (idle-chunk reclaim / trim is a later phase).
 *
 * **Thread safety**: guarded by an [ArenaLock]. Every mutation of the chunk
 * bookkeeping ([carve] / [returnRun] / [reclaim] / [close]) runs under the lock,
 * so a shared central arena can be carved-from and returned-to by threads not
 * pinned to a single EventLoop (Coroutine-mode `Channel.allocator` users, the
 * NWConnection GCD worker pool). An EL-pinned allocator whose arena is never
 * shared pays only the uncontended lock fast-path, and only on the miss path
 * (the freelist cache absorbs hits). `backing.retain` / `release` are already
 * atomic (PR #800), so the lock covers only the run/subpage bookkeeping
 * ([PooledChunk] / [PoolChunk] / [chunks]).
 *
 * @param sizeClasses the shared size-class table (selects run vs subpage and sizes).
 * @param newChunkBacking allocates a fresh `CHUNK_SIZE` owns-memory backing buffer.
 * @param newChunkView builds a platform view over a chunk backing at a byte offset,
 *   carrying the run-binding.
 */
internal class ChunkArena(
    private val sizeClasses: SizeClasses,
    private val newChunkBacking: () -> IoBuf,
    private val newChunkView: ChunkViewFactory,
) {
    private val chunks = ArrayList<PooledChunk>()
    private val lock = ArenaLock()

    /**
     * Per-size-class subpage pool heads shared across all [chunks] (Netty
     * `PoolArena.smallSubpagePools`). [carve] reuses a partially-free subpage from
     * *any* chunk through these heads before carving a fresh subpage run, so a small
     * allocation is not stranded on whichever chunk the linear scan reaches first.
     * Guarded by [lock] here; a per-class head lock that lets the subpage fast path
     * bypass [lock] is a follow-up.
     */
    private val smallSubpagePools: Array<PoolSubpage> =
        Array(sizeClasses.nSubpages) { PoolSubpage.newHead(sizeClasses.pageShifts) }

    /**
     * Per-size-class spin locks guarding the matching [smallSubpagePools] head's pool
     * list and the subpage bitmaps reachable from it. The subpage fast path takes only
     * its class's head lock (no [lock]), so distinct size classes — and the run path —
     * carve concurrently. CAS spin locks (not [ArenaLock]) keep this to one `AtomicInt`
     * per class with no OS-resource lifecycle (Netty's per-class head lock without the
     * ~30 `pthread_mutex` per arena).
     */
    private val headLocks: Array<SpinLock> = Array(sizeClasses.nSubpages) { SpinLock() }

    /**
     * The cross-chunk subpage pool head for size class [sizeIdx]. The returned
     * sentinel's pool list is mutated only under `headLocks[sizeIdx]` (or
     * single-threaded teardown).
     */
    internal fun subpageHead(sizeIdx: Int): PoolSubpage = smallSubpagePools[sizeIdx]

    // Set by [close] after the lock guard runs and before the lock is destroyed.
    // A view held across [close] (e.g. an in-flight write not yet flushed) releases
    // afterwards on the single-threaded teardown path; [returnRun] reads this flag
    // to skip the now-destroyed lock and return the run directly. @Volatile gives
    // the teardown-thread read visibility of the close-thread write (the same thread
    // under the single-threaded teardown contract, but @Volatile is correct and free).
    @kotlin.concurrent.Volatile
    private var closed: Boolean = false

    /** Number of resident chunks (test/diagnostic observability). */
    internal val chunkCount: Int get() = chunks.size

    // Cumulative chunk-arena counters backing AllocatorStats.snapshot. Written
    // under [lock] (carve / reclaim / close) and read by the OT collection-cycle
    // thread via the snapshot; @Volatile gives that reader eventual consistency at
    // the same convention PooledAllocator uses for its own cumulative counters.
    // Read-side is via the snapshot — the public surface lives on
    // [AllocatorStats.cumulativeChunksAllocated] / [AllocatorStats.cumulativeChunksFreed],
    // wired by [PooledAllocator.buildSnapshot].
    @kotlin.concurrent.Volatile
    internal var cumulativeChunksAllocated: Long = 0L
        private set

    @kotlin.concurrent.Volatile
    internal var cumulativeChunksFreed: Long = 0L
        private set

    /**
     * Carves a buffer of size class [sizeIdx] from a chunk and returns it as a view.
     * Small classes (`sizeIdx <= smallMaxSizeIdx`) come from a subpage bitmap — the
     * fast path takes only the per-class head lock ([headLocks]); larger ones, and a
     * subpage miss, take the arena [lock]. See [carveSubpage] / [carveRun].
     */
    fun carve(sizeIdx: Int): IoBuf {
        val classSize = sizeClasses.sizeIdx2size(sizeIdx)
        return if (sizeIdx <= sizeClasses.smallMaxSizeIdx) carveSubpage(sizeIdx, classSize) else carveRun(classSize)
    }

    /**
     * Subpage carve. Fast path: reuse a partially-free subpage under the per-class head
     * lock only, so it never touches the arena [lock] and runs concurrently with other
     * classes' carves and the run path. Miss: take the arena [lock] (to carve a fresh
     * subpage run) then the head lock (to link + allocate). The head lock is released
     * before the arena lock on the fast path, and re-acquired *inside* the arena lock on
     * the miss path — arena -> head is the only nesting order, so no deadlock (the free
     * path likewise never escalates a head lock to the arena lock).
     */
    private fun carveSubpage(sizeIdx: Int, classSize: Int): IoBuf {
        val head = smallSubpagePools[sizeIdx]
        val headLock = headLocks[sizeIdx]
        headLock.withLock {
            val hit = tryPoolHit(head, classSize)
            if (hit != null) return hit
        }
        return lock.withLock { carveSubpageMiss(sizeIdx, classSize, head, headLock) }
    }

    /**
     * Subpage miss path: caller holds the arena [lock]. Re-checks the pool under the
     * head lock (another EventLoop may have added a subpage of this class since the
     * fast-path head-lock release), else carves a fresh subpage run from the first
     * chunk with a free run, else a fresh chunk; the new subpage links itself into
     * [head] for reuse.
     */
    private fun carveSubpageMiss(sizeIdx: Int, classSize: Int, head: PoolSubpage, headLock: SpinLock): IoBuf =
        headLock.withLock {
            val hit = tryPoolHit(head, classSize)
            if (hit != null) return@withLock hit
            for (i in chunks.indices) {
                val handle = chunks[i].allocateNewSubpage(sizeIdx, head)
                if (handle != PoolChunk.NO_HANDLE) return@withLock makeView(chunks[i], handle, classSize)
            }
            val fresh = newChunk()
            val handle = fresh.allocateNewSubpage(sizeIdx, head)
            check(handle != PoolChunk.NO_HANDLE) {
                "fresh chunk failed to carve subpage class $sizeIdx ($classSize bytes)"
            }
            makeView(fresh, handle, classSize)
        }

    /**
     * Reuses the first partially-free subpage in [head]'s pool, returning its view, or
     * `null` when the pool is empty. Caller holds the head lock for [head]'s class.
     */
    private fun tryPoolHit(head: PoolSubpage, classSize: Int): IoBuf? {
        val first = head.next
        if (first != null && first !== head) {
            val handle = first.allocate()
            if (handle != PoolSubpage.NO_HANDLE) {
                val owner = checkNotNull(first.ownerChunk) { "pooled subpage missing owner chunk" }
                return makeView(owner, handle, classSize)
            }
        }
        return null
    }

    /** Run carve under the arena [lock]: first chunk with a free run, else a fresh chunk. */
    private fun carveRun(classSize: Int): IoBuf = lock.withLock {
        for (i in chunks.indices) {
            val handle = chunks[i].carveRun(classSize)
            if (handle != PoolChunk.NO_HANDLE) return@withLock makeView(chunks[i], handle, classSize)
        }
        val fresh = newChunk()
        val handle = fresh.carveRun(classSize)
        check(handle != PoolChunk.NO_HANDLE) { "fresh chunk failed to carve run class ($classSize bytes)" }
        makeView(fresh, handle, classSize)
    }

    /** Allocates a fresh chunk, tracks it, and counts it. Runs under [lock]. */
    private fun newChunk(): PooledChunk {
        val fresh = PooledChunk(newChunkBacking(), PoolChunk(sizeClasses))
        fresh.arena = this
        chunks.add(fresh)
        cumulativeChunksAllocated++
        return fresh
    }

    /**
     * Returns a view's run/subpage element to its chunk on the view's final release —
     * possibly on a thread other than the one that carved it (cross-thread free).
     *
     * A subpage element frees under the per-class head lock; only if that empties the
     * subpage does its run return to the chunk under the arena [lock]. The head lock is
     * released before the arena lock is taken (strict alternation — never head ->
     * arena). A whole run frees under the arena lock. The view's `backing` reference is
     * dropped last (atomic, independent of either lock).
     */
    internal fun returnRun(pc: PooledChunk, handle: Long) {
        if (closed) {
            // Post-close teardown: the EventLoop threads are stopped (single-threaded
            // teardown contract, see [close] / [PooledAllocator.close]) and the locks are
            // gone, so there is no concurrent carve/return to serialise against. Free
            // directly — the combined single-threaded path.
            pc.freeRun(handle)
            return
        }
        if (PoolChunk.isSubpage(handle)) {
            val sizeIdx = pc.poolChunk.subpageSizeIdx(handle)
            val runEmptied = headLocks[sizeIdx].withLock {
                pc.poolChunk.freeSubpageElement(handle, smallSubpagePools[sizeIdx])
            }
            if (runEmptied) lock.withLock { pc.poolChunk.returnEmptiedSubpageRun(handle) }
        } else {
            lock.withLock { pc.poolChunk.freeRunHandle(handle) }
        }
        pc.backing.release()
    }

    private fun makeView(pc: PooledChunk, handle: Long, classSize: Int): IoBuf {
        val byteOffset = pc.poolChunk.byteOffset(handle)
        pc.retainForCarve()
        return newChunkView(pc.backing, byteOffset, classSize, pc, handle)
    }

    /**
     * Detaches every preserved fully-free subpage from its per-class pool and returns
     * its run to the owning chunk, so a chunk left resident only by a retained
     * last-subpage becomes fully free and [reclaim]'s freeBytes scan can release it.
     * Runs under the arena [lock]; each pool walk takes its class head lock
     * (arena -> head, the consistent order). Called at the start of [reclaim] (the cold
     * trim pass), so per-class fast-path warmth is rebuilt by the next allocate rather
     * than pinned against reclaim.
     */
    private fun drainPreservedSubpages() {
        for (sizeIdx in smallSubpagePools.indices) {
            val head = smallSubpagePools[sizeIdx]
            headLocks[sizeIdx].withLock {
                var sp = head.next
                while (sp != null && sp !== head) {
                    val next = sp.next
                    if (sp.isFullyFree) {
                        val owner = checkNotNull(sp.ownerChunk) { "pooled subpage missing owner chunk" }
                        sp.detachFromPool()
                        owner.poolChunk.returnSubpageRun(sp.runOffset, sp.runSize ushr sp.pageShifts)
                    }
                    sp = next
                }
            }
        }
    }

    /**
     * Frees the backing of fully-idle chunks (no live or cached carve), keeping at
     * most [warmReserve] idle chunks resident to avoid alloc/free thrashing. Called
     * from the per-EventLoop trim pass after cached views have returned their runs.
     * Runs under [lock].
     *
     * This is a keel simplification, **not** Netty's chunk lifecycle. Netty has no
     * count-based reserve: its `PoolChunkList` ring (`qInit`/`q000`..`q100`) destroys
     * a chunk the moment it becomes fully free in `q000` (`prevList == null`), while
     * `qInit`'s self-loop keeps low-peak-usage chunks resident — an emergent, not
     * fixed-count, warm set. The flat "free idle beyond [warmReserve]" rule here
     * approximates that without a usage-threshold ring; porting the ring is a later
     * phase.
     */
    fun reclaim(warmReserve: Int) = lock.withLock {
        drainPreservedSubpages()
        var idleKept = 0
        var i = 0
        while (i < chunks.size) {
            val pc = chunks[i]
            // `freeBytes == chunkSize` means every run is free, so the chunk holds no
            // subpages (a subpage occupies a run) — no per-class head pool points into
            // it, so freeing its backing cannot dangle a head. Stricter than a per-view
            // live count: a preserved fully-free subpage keeps its run (and so its
            // chunk) resident, matching Netty's subpage retention.
            if (pc.isFullyFree) {
                if (idleKept < warmReserve) {
                    idleKept++
                    i++
                } else {
                    // Drop the arena's own reference: refCount 1 -> 0 -> freeBacking
                    // releases the chunk's memory. Safe because no view references it.
                    pc.backing.release()
                    chunks.removeAt(i)
                    cumulativeChunksFreed++
                }
            } else {
                i++
            }
        }
    }

    /**
     * Drops the arena's own reference to every tracked chunk and clears the
     * list, then releases the [ArenaLock]'s OS resource. Idle chunks (no live
     * views) free their backing immediately as their refCount drops to 0; chunks
     * with live views stay alive on the views' references and free themselves once
     * the last view is released.
     *
     * Called from [PooledAllocator.close] once, after the EventLoop threads have
     * stopped (single-threaded teardown), so no concurrent [carve] / [returnRun]
     * races the lock destroy. After [close] the arena is empty and must not service
     * further requests.
     */
    fun close() {
        lock.withLock {
            for (i in chunks.indices) chunks[i].backing.release()
            cumulativeChunksFreed += chunks.size.toLong()
            chunks.clear()
        }
        // Mark closed before destroying the lock so a post-close [returnRun] (a view
        // held across close) sees it and takes the direct, lock-free path.
        closed = true
        lock.close()
    }
}
