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
 * **Thread safety**: none of its own; the owning [PooledAllocator] serialises via
 * its single arena lock. When [owningAllocator] is non-null (production path)
 * [freeRun] takes the arena lock before mutating chunk state. When it is null
 * (direct [PoolChunk] tests / bench harnesses that wrap a chunk without an
 * allocator) [freeRun] runs unsynchronised, relying on the test pinning a
 * single thread to the chunk.
 *
 * @property backing the chunk's memory (a single owns-memory [IoBuf] of `CHUNK_SIZE`).
 *   Its reference count tracks live + cached carves: `1` (this chunk's own hold)
 *   plus one per outstanding view.
 * @property poolChunk the run/subpage bookkeeper over [backing]'s pages.
 * @property owningAllocator the [PooledAllocator] this chunk lives under, or
 *   `null` when the chunk was constructed standalone in a test. The allocator
 *   reference lets [freeRun] take the arena lock during release so subpage /
 *   run mutations stay serialised with the allocate miss path. Set once at
 *   chunk creation and never mutated.
 */
class PooledChunk internal constructor(
    internal val backing: IoBuf,
    internal val poolChunk: PoolChunk,
    internal val owningAllocator: PooledAllocator? = null,
) {
    private val subpageHeads = arrayOfNulls<PoolSubpage>(poolChunk.sizeClasses.nSubpages)

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
     *
     * Lock acquisition (when [owningAllocator] is non-null): the allocator's
     * single arena lock is taken around the [PoolChunk.free] call so the
     * release path serialises with the allocate miss path and the trim pass.
     * When [owningAllocator] is `null` (or already closed) the bookkeeping
     * runs unsynchronised — the post-close branch is safe because
     * [PooledAllocator.close]'s contract forbids concurrent allocate.
     */
    internal fun freeRun(handle: Long) {
        val allocator = owningAllocator
        if (allocator == null || allocator.isClosed) {
            doFreeRun(handle)
            return
        }
        allocator.withArenaLock {
            doFreeRun(handle)
        }
    }

    private fun doFreeRun(handle: Long) {
        if (PoolChunk.isSubpage(handle)) {
            poolChunk.free(handle, subpageHead(poolChunk.subpageSizeIdx(handle)))
        } else {
            poolChunk.free(handle, head = null)
        }
        backing.release()
        liveCarves--
    }

    private fun subpageHead(sizeIdx: Int): PoolSubpage =
        subpageHeads[sizeIdx] ?: PoolSubpage.newHead(poolChunk.pageShifts).also { subpageHeads[sizeIdx] = it }
}
