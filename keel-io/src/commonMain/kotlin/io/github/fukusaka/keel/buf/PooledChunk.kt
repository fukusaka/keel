package io.github.fukusaka.keel.buf

/**
 * Pairs a [PoolChunk] (the pure run/subpage bookkeeper, #718) with the real
 * backing [IoBuf] it carves views from.
 *
 * [PoolChunk] is intentionally backing-agnostic: it hands out integer handles
 * and computes byte offsets, but holds no memory. [PooledChunk] supplies the
 * backing (a `CHUNK_SIZE` owns-memory buffer); the per-size-class subpage pool
 * heads live on the owning [ChunkArena] and are shared across chunks, so a small
 * allocation reuses a partially-free subpage from *any* chunk before carving a new
 * run (Netty's `PoolArena.smallSubpagePools`). A carved subpage is tagged with its
 * owning chunk ([PoolSubpage.ownerChunk]) so a cross-chunk pool hit resolves back to
 * this backing.
 *
 * **Thread safety**: none of its own; the owning per-EventLoop allocator serialises.
 *
 * @property backing the chunk's memory (a single owns-memory [IoBuf] of `CHUNK_SIZE`).
 *   Its reference count tracks live + cached carves: `1` (this chunk's own hold)
 *   plus one per outstanding view.
 * @property poolChunk the run/subpage bookkeeper over [backing]'s pages.
 */
class PooledChunk internal constructor(
    internal val backing: IoBuf,
    internal val poolChunk: PoolChunk,
) {
    /**
     * Back-reference to the owning [ChunkArena], set by [ChunkArena.carve] when the
     * arena creates this chunk. [ChunkBackedIoBuf.returnChunkRun] uses it to route a
     * view's run return through [ChunkArena.returnRun] (under the arena lock) instead
     * of calling [freeRun] directly, so a cross-thread free serialises against
     * concurrent carve / return on the same arena; [freeRun] also reads it to resolve
     * the per-arena subpage pool head. `null` only for a chunk created outside an
     * arena (test fixtures), where [freeRun] is called directly and frees runs only.
     */
    internal var arena: ChunkArena? = null

    /**
     * Outstanding carves (live + cached views) referencing this chunk. Mirrors the
     * extra references on [backing] beyond the arena's own hold, so `liveCarves == 0`
     * means the chunk is fully idle and reclaimable. Maintained on the single
     * owning thread (per-EventLoop), like the rest of the allocator's bookkeeping.
     */
    internal var liveCarves: Int = 0
        private set

    /** True when no view references this chunk — safe to reclaim. */
    internal val isIdle: Boolean get() = liveCarves == 0

    /** Retains the backing for a freshly carved view and counts the carve. */
    internal fun retainForCarve() {
        backing.retain()
        liveCarves++
    }

    /** Allocates a run of [classSize] bytes; returns the handle or [PoolChunk.NO_HANDLE]. */
    internal fun carveRun(classSize: Int): Long = poolChunk.allocateRun(classSize)

    /**
     * Carves a fresh subpage run of size class [sizeIdx] from this chunk and links it
     * into the arena's cross-chunk pool [head], tagging the new [PoolSubpage] with
     * this chunk so a later pool hit resolves it back to this backing. Returns the
     * first element's handle, or [PoolChunk.NO_HANDLE] when this chunk has no free
     * run. Reuse of an existing partially-free subpage is the arena's job (it checks
     * [head] before carving a new one here).
     */
    internal fun allocateNewSubpage(sizeIdx: Int, head: PoolSubpage): Long {
        val handle = poolChunk.allocateSubpage(sizeIdx, head)
        if (handle != PoolChunk.NO_HANDLE) poolChunk.subpageAt(handle).ownerChunk = this
        return handle
    }

    /**
     * Returns the run/subpage element at [handle] to this chunk and drops one
     * reference on [backing]. For a subpage the run is reclaimed only once its last
     * element is freed ([PoolChunk.free]); the size-class pool head comes from the
     * owning [arena] so a freed subpage re-joins the cross-chunk pool. A run (or any
     * free on an arena-less test chunk) frees with a `null` head.
     */
    internal fun freeRun(handle: Long) {
        val head = if (PoolChunk.isSubpage(handle)) arena?.subpageHead(poolChunk.subpageSizeIdx(handle)) else null
        poolChunk.free(handle, head)
        backing.release()
        liveCarves--
    }

    /**
     * Unlinks this chunk's pooled subpages from the arena heads, called just before
     * the chunk is reclaimed so a preserved fully-free subpage does not leave the
     * pool head pointing into this chunk's freed backing.
     */
    internal fun detachPooledSubpages() = poolChunk.detachAllSubpages()
}
