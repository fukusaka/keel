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
     * Carves a buffer of size class [sizeIdx] from a chunk and returns it as a
     * view. Small classes (`sizeIdx <= smallMaxSizeIdx`) come from a subpage
     * bitmap, larger ones from a page run. Runs under [lock].
     */
    fun carve(sizeIdx: Int): IoBuf = lock.withLock {
        val classSize = sizeClasses.sizeIdx2size(sizeIdx)
        val subpage = sizeIdx <= sizeClasses.smallMaxSizeIdx
        for (i in chunks.indices) {
            val pc = chunks[i]
            val handle = if (subpage) pc.carveSubpage(sizeIdx) else pc.carveRun(classSize)
            if (handle != PoolChunk.NO_HANDLE) return@withLock makeView(pc, handle, classSize)
        }
        val fresh = PooledChunk(newChunkBacking(), PoolChunk(sizeClasses))
        fresh.arena = this
        chunks.add(fresh)
        cumulativeChunksAllocated++
        val handle = if (subpage) fresh.carveSubpage(sizeIdx) else fresh.carveRun(classSize)
        check(handle != PoolChunk.NO_HANDLE) { "fresh chunk failed to carve size class $sizeIdx ($classSize bytes)" }
        makeView(fresh, handle, classSize)
    }

    /**
     * Returns a view's run to its chunk under [lock]. Called from
     * [ChunkBackedIoBuf.returnChunkRun] on the view's final release — which may
     * run on a thread other than the one that carved it (cross-thread free), so
     * the lock serialises it against concurrent [carve] / [returnRun] on the same
     * arena. [PooledChunk.freeRun] also drops the carve's `backing` reference.
     */
    internal fun returnRun(pc: PooledChunk, handle: Long) {
        if (closed) {
            // Post-close teardown: the EventLoop threads are stopped (single-threaded
            // teardown contract, see [close] / [PooledAllocator.close]) and the lock is
            // already destroyed, so there is no concurrent carve/return to serialise
            // against. Return the run directly — the same path the pre-lock code took.
            pc.freeRun(handle)
            return
        }
        lock.withLock { pc.freeRun(handle) }
    }

    private fun makeView(pc: PooledChunk, handle: Long, classSize: Int): IoBuf {
        val byteOffset = pc.poolChunk.byteOffset(handle)
        pc.retainForCarve()
        return newChunkView(pc.backing, byteOffset, classSize, pc, handle)
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
        var idleKept = 0
        var i = 0
        while (i < chunks.size) {
            val pc = chunks[i]
            if (pc.isIdle) {
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
