package io.github.fukusaka.keel.buf

/**
 * A poolable [IoBuf] that is a view carved from a [PooledChunk]'s run/subpage
 * (the jemalloc-style chunk back-end). On final release the view returns its run
 * to the owning chunk instead of freeing platform memory.
 *
 * The binding `(chunkPool, chunkHandle)` is **pool-back-end state** stored as
 * nullable fields on the concrete platform buffers ([NativeIoBuf] / [DirectIoBuf])
 * — the same place their intrusive freelist `nextLink` lives — rather than on the
 * [IoBufOwner]. The owner is reset to the canonical pool owner on every pool
 * recycle (so decorators don't nest, PR #613); the chunk binding is per-view
 * physical identity that is fixed for the buffer's life, so it must survive that
 * reset. Keeping it in a field (not the owner) makes it immune to the reset by
 * construction. (The owner field doubling as the decorator hook is a separate,
 * orthogonal concern — see task_d887d332.)
 *
 * `null` [chunkPool] means the buffer is not chunk-backed (a fresh owns-memory
 * allocation, an external wrap, or a chunk backing itself), so its `freeBacking`
 * keeps its platform behaviour. The platform `freeBacking` branches on
 * `chunkPool != null` to call [returnChunkRun].
 */
internal interface ChunkBackedIoBuf {
    /** The chunk this view was carved from, or `null` when not chunk-backed. */
    var chunkPool: PooledChunk?

    /** The run/subpage handle within [chunkPool] (valid when [chunkPool] is non-null). */
    var chunkHandle: Long

    /**
     * Returns this view's run to its chunk and drops the chunk reference.
     * Idempotent: clears [chunkPool] first so a second call (e.g. a repeated
     * `freeBacking`) is a no-op and the run is never double-freed.
     *
     * Routes through [ChunkArena.returnRun] (under the arena lock) when the chunk
     * has an owning arena, so a cross-thread free — the freeing thread is not
     * necessarily the one that carved the view — serialises against concurrent
     * carve / return on the same arena. A chunk created outside an arena (test
     * fixtures, `arena == null`) falls back to a direct [PooledChunk.freeRun].
     */
    fun returnChunkRun() {
        val pool = chunkPool ?: return
        chunkPool = null
        val arena = pool.arena
        if (arena != null) arena.returnRun(pool, chunkHandle) else pool.freeRun(chunkHandle)
    }
}
