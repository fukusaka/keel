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
 * **Thread safety**: none of its own. The owning [ChunkArena] serialises run
 * bookkeeping under its arena lock and subpage bookkeeping under the matching
 * per-class head lock; the [backing] reference count is atomic.
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
     * view's run return through [ChunkArena.returnRun] (under the arena / head locks)
     * instead of calling [freeRun] directly; [freeRun] also reads it to resolve the
     * per-arena subpage pool head. `null` only for a chunk created outside an arena
     * (test fixtures), where [freeRun] is called directly and frees runs only.
     */
    internal var arena: ChunkArena? = null

    /**
     * True when every run is free, so the chunk holds no live views and no subpages —
     * safe for [ChunkArena.reclaim] to release its backing. Per-run, not per-view: a
     * preserved fully-free subpage keeps its run, so it keeps the chunk resident
     * (matching Netty's subpage retention) rather than being reclaimed mid-pool. This
     * decouples reclaim from the head-lock domain, so the subpage fast path never races
     * it.
     */
    internal val isFullyFree: Boolean get() = poolChunk.freeBytes == poolChunk.chunkSize

    /** Retains the backing for a freshly carved view (the atomic per-view reference). */
    internal fun retainForCarve() {
        backing.retain()
    }

    /** Allocates a run of [classSize] bytes; returns the handle or [PoolChunk.NO_HANDLE]. */
    internal fun carveRun(classSize: Int): Long = poolChunk.allocateRun(classSize)

    /**
     * Carves a fresh subpage run of size class [sizeIdx] from this chunk and links it
     * into the arena's cross-chunk pool [head], tagging the new [PoolSubpage] with this
     * chunk so a later pool hit resolves it back to this backing. Returns the first
     * element's handle, or [PoolChunk.NO_HANDLE] when this chunk has no free run.
     * Reuse of an existing partially-free subpage is the arena's job (it checks [head]
     * before carving a new one here). Caller holds the arena lock and the [head]'s
     * class head lock.
     */
    internal fun allocateNewSubpage(sizeIdx: Int, head: PoolSubpage): Long {
        val handle = poolChunk.allocateSubpage(sizeIdx, head)
        if (handle != PoolChunk.NO_HANDLE) poolChunk.subpageAt(handle).ownerChunk = this
        return handle
    }

    /**
     * Frees the run/subpage element at [handle] and drops one reference on [backing]
     * (combined, single-threaded teardown path — the live path splits the subpage
     * element free and run return across the head and arena locks in
     * [ChunkArena.returnRun]). For a subpage the size-class pool head comes from the
     * owning [arena] so a freed subpage re-joins the cross-chunk pool; a run (or any
     * free on an arena-less test chunk) frees with a `null` head.
     */
    internal fun freeRun(handle: Long) {
        val head = if (PoolChunk.isSubpage(handle)) arena?.subpageHead(poolChunk.subpageSizeIdx(handle)) else null
        poolChunk.free(handle, head)
        backing.release()
    }
}
