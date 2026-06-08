package io.github.fukusaka.keel.buf

/**
 * Pairs a [PoolChunk] (the pure run/subpage bookkeeper, #718) with the real
 * backing [IoBuf] it carves views from, and owns this chunk's per-size-class
 * subpage pool heads.
 *
 * [PoolChunk] is intentionally backing-agnostic: it hands out integer handles
 * and computes byte offsets, but holds no memory. [PooledChunk] supplies the
 * backing (a `CHUNK_SIZE` owns-memory buffer) and the subpage [PoolSubpage.newHead]
 * sentinels, so a small allocation can reuse a partially-free subpage *within this
 * chunk* before carving a new run. (Cross-chunk subpage reuse — a single head per
 * size class shared across chunks — is a later optimisation; per-chunk heads keep
 * the view's run-binding minimal: just `(this, handle)`.)
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
    private val subpageHeads = arrayOfNulls<PoolSubpage>(poolChunk.sizeClasses.nSubpages)

    /** Allocates a run of [classSize] bytes; returns the handle or [PoolChunk.NO_HANDLE]. */
    internal fun carveRun(classSize: Int): Long = poolChunk.allocateRun(classSize)

    /**
     * Allocates one small element of size class [sizeIdx] — reusing a
     * partially-free subpage in this chunk if one exists, else carving a fresh
     * subpage run. Returns the handle or [PoolChunk.NO_HANDLE] when no run is free.
     */
    internal fun carveSubpage(sizeIdx: Int): Long {
        val head = subpageHead(sizeIdx)
        val first = head.next
        if (first != null && first !== head) {
            val handle = first.allocate()
            if (handle != PoolSubpage.NO_HANDLE) return handle
        }
        return poolChunk.allocateSubpage(sizeIdx, head)
    }

    /**
     * Returns the run/subpage element at [handle] to this chunk and drops one
     * reference on [backing]. For a subpage the run is reclaimed only once its
     * last element is freed (handled by [PoolChunk.free]).
     */
    internal fun freeRun(handle: Long) {
        if (PoolChunk.isSubpage(handle)) {
            poolChunk.free(handle, subpageHead(poolChunk.subpageSizeIdx(handle)))
        } else {
            poolChunk.free(handle, head = null)
        }
        backing.release()
    }

    private fun subpageHead(sizeIdx: Int): PoolSubpage =
        subpageHeads[sizeIdx] ?: PoolSubpage.newHead(poolChunk.pageShifts).also { subpageHeads[sizeIdx] = it }
}
