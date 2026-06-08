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
) {
    private val chunks = ArrayList<PooledChunk>()

    /**
     * Carves a buffer of size class [sizeIdx] from a chunk and returns it as a
     * view. Small classes (`sizeIdx <= smallMaxSizeIdx`) come from a subpage
     * bitmap, larger ones from a page run.
     */
    fun carve(sizeIdx: Int): IoBuf {
        val classSize = sizeClasses.sizeIdx2size(sizeIdx)
        val subpage = sizeIdx <= sizeClasses.smallMaxSizeIdx
        for (i in chunks.indices) {
            val pc = chunks[i]
            val handle = if (subpage) pc.carveSubpage(sizeIdx) else pc.carveRun(classSize)
            if (handle != PoolChunk.NO_HANDLE) return makeView(pc, handle, classSize)
        }
        val fresh = PooledChunk(newChunkBacking(), PoolChunk(sizeClasses))
        chunks.add(fresh)
        val handle = if (subpage) fresh.carveSubpage(sizeIdx) else fresh.carveRun(classSize)
        check(handle != PoolChunk.NO_HANDLE) { "fresh chunk failed to carve size class $sizeIdx ($classSize bytes)" }
        return makeView(fresh, handle, classSize)
    }

    private fun makeView(pc: PooledChunk, handle: Long, classSize: Int): IoBuf {
        val byteOffset = pc.poolChunk.byteOffset(handle)
        pc.backing.retain()
        return newChunkView(pc.backing, byteOffset, classSize, pc, handle)
    }
}
