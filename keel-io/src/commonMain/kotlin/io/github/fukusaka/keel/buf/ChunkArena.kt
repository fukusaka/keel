package io.github.fukusaka.keel.buf

/**
 * Platform seam that builds a non-owning view over a chunk backing at a byte
 * offset, carrying the run-binding `(pooledChunk, handle)` — a `NativeIoBuf`
 * pointer view on Native, a `DirectIoBuf` `slice()` on JVM.
 */
internal typealias ChunkViewFactory =
    (backing: IoBuf, byteOffset: Int, length: Int, pooledChunk: PooledChunk, handle: Long) -> IoBuf

/**
 * The chunk back-end for one per-EventLoop [PooledAllocator]: a list of
 * [PooledChunk]s that satisfy pool *misses* by carving a run/subpage view out of
 * a large pre-allocated chunk, instead of a `malloc` / `allocateDirect` per buffer.
 *
 * [carve] is invoked only on a cache miss (the per-size-class freelist sits in
 * front and absorbs the hot path), so the linear scan over chunks is off the hot
 * path. When no existing chunk can satisfy the request a fresh chunk is allocated
 * via [newChunkBacking]. The view itself is built by the platform [newChunkView]
 * seam (a `NativeIoBuf` pointer view / a `DirectIoBuf` slice) carrying the
 * `(PooledChunk, handle)` run-binding.
 *
 * **Lifecycle**: [carve] retains the chunk backing once per view; the view's
 * `freeBacking` (via [ChunkBackedIoBuf.returnChunkRun]) releases it and returns
 * the run. Chunks are **not reclaimed** in this phase — they are held for the
 * allocator's lifetime (idle-chunk reclaim / trim is a later phase).
 *
 * **Thread safety**: none of its own; confined to its owning EventLoop allocator.
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
    /**
     * The owning [PooledAllocator] (used as `PooledChunk.owningAllocator`).
     * Nullable for direct ChunkArena tests that exercise the chunk back-end
     * without an allocator (e.g. `ChunkArenaCarveBenchmark`); the release path
     * then runs unsynchronised, which is correct because those tests pin a
     * single thread to the arena.
     */
    private val owningAllocator: PooledAllocator? = null,
) {
    private val chunks = ArrayList<PooledChunk>()

    /** Number of resident chunks (test/diagnostic observability). */
    internal val chunkCount: Int get() = chunks.size

    /**
     * Carves a buffer of size class [sizeIdx] from a chunk and returns it as a
     * view.
     *
     * - When [head] is non-null (subpage size class slow path): carves a fresh
     *   subpage run from a chunk and installs a new [PoolSubpage] into [head]'s
     *   chain (arena-level chain, shared across chunks). Callers walk the chain
     *   themselves to find a partially-free subpage before reaching this method;
     *   this method only handles the chain-miss case.
     * - When [head] is null (run / normal size class): carves a page run.
     *
     * On either path, iterates [chunks] looking for one that can satisfy the
     * request; if none can, allocates a fresh chunk and uses it.
     */
    fun carve(sizeIdx: Int, head: PoolSubpage? = null): IoBuf {
        val classSize = sizeClasses.sizeIdx2size(sizeIdx)
        val isSubpage = sizeIdx <= sizeClasses.smallMaxSizeIdx
        // For subpage classes called without an explicit head (test / direct
        // ChunkArena bench paths), allocate a transient sentinel for this single
        // carve. Production callers always supply the arena-level head from
        // PooledAllocator.subpageHeads so cross-chunk subpage reuse fires; the
        // transient head here just lets PoolSubpage.create's addToPool target
        // a valid sentinel without polluting any persistent chain.
        val effectiveHead =
            if (isSubpage) head ?: PoolSubpage.newHead(sizeClasses.pageShifts, headIndex = sizeIdx)
            else null
        for (i in chunks.indices) {
            val pc = chunks[i]
            val handle = if (isSubpage) pc.carveNewSubpage(sizeIdx, effectiveHead!!) else pc.carveRun(classSize)
            if (handle != PoolChunk.NO_HANDLE) return makeView(pc, handle, classSize)
        }
        val fresh = PooledChunk(newChunkBacking(), PoolChunk(sizeClasses), owningAllocator)
        chunks.add(fresh)
        val handle = if (isSubpage) fresh.carveNewSubpage(sizeIdx, effectiveHead!!) else fresh.carveRun(classSize)
        check(handle != PoolChunk.NO_HANDLE) { "fresh chunk failed to carve size class $sizeIdx ($classSize bytes)" }
        return makeView(fresh, handle, classSize)
    }

    /**
     * Builds an [IoBuf] view at [handle] in [pc], retaining the chunk's backing
     * for the new view. Exposed `internal` so [PooledAllocator]'s subpage fast
     * path can build a view after walking the arena-level chain without going
     * back through [carve] (which is the slow path).
     */
    @Suppress("IoBufLeak") // Returns ownership to caller.
    internal fun makeView(pc: PooledChunk, handle: Long, classSize: Int): IoBuf {
        val byteOffset = pc.poolChunk.byteOffset(handle)
        pc.retainForCarve()
        return newChunkView(pc.backing, byteOffset, classSize, pc, handle)
    }

    /**
     * Frees the backing of fully-idle chunks (no live or cached carve), keeping at
     * most [warmReserve] idle chunks resident to avoid alloc/free thrashing. Called
     * from the per-EventLoop trim pass after cached views have returned their runs.
     *
     * This is a keel simplification, **not** Netty's chunk lifecycle. Netty has no
     * count-based reserve: its `PoolChunkList` ring (`qInit`/`q000`..`q100`) destroys
     * a chunk the moment it becomes fully free in `q000` (`prevList == null`), while
     * `qInit`'s self-loop keeps low-peak-usage chunks resident — an emergent, not
     * fixed-count, warm set. The flat "free idle beyond [warmReserve]" rule here
     * approximates that without a usage-threshold ring; porting the ring is a later
     * phase.
     */
    fun reclaim(warmReserve: Int) {
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
                }
            } else {
                i++
            }
        }
    }

    /**
     * Drops the arena's own reference to every tracked chunk and clears the
     * list. Idle chunks (no live views) free their backing immediately as
     * their refCount drops to 0; chunks with live views stay alive on the
     * views' references and free themselves once the last view is released.
     *
     * Called from [PooledAllocator.close] once. After [close] the arena is
     * empty and must not service further [carve] requests.
     */
    fun close() {
        for (i in chunks.indices) chunks[i].backing.release()
        chunks.clear()
    }
}
