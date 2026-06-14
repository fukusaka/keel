package io.github.fukusaka.keel.buf

/**
 * Pairs a [PoolChunk] (the pure run/subpage bookkeeper, #718) with the real
 * backing [IoBuf] it carves views from.
 *
 * [PoolChunk] is intentionally backing-agnostic: it hands out integer handles
 * and computes byte offsets, but holds no memory. [PooledChunk] supplies the
 * backing (a `CHUNK_SIZE` owns-memory buffer).
 *
 * **Subpage chain ownership has moved to the arena** ([PooledAllocator]
 * `subpageHeads[]`). The per-size-class doubly-linked list spans every chunk
 * of an allocator, mirroring Netty's `smallSubpagePools[]` at `PoolArena`.
 * Each [PoolSubpage] now carries an `owningChunk` back-pointer so the
 * allocator-level chain walker can locate the chunk to build a view from.
 * This is the structural prerequisite for per-size-class subpage head locks
 * (Option B) — without arena-level chain heads, a per-class lock could not
 * guard subpage chain operations across chunks, and the parallelism that
 * makes Option B worth the cost would not be reachable.
 *
 * **Thread safety**: none of its own; the owning [PooledAllocator] serialises
 * via its arena lock + per-class subpage head locks. When [owningAllocator]
 * is non-null (production path) [freeRun] takes the right locks before
 * mutating chunk state. When it is null (direct [PoolChunk] tests that wrap
 * a chunk without an allocator) [freeRun] runs unsynchronised, relying on the
 * test running on a single thread.
 *
 * @property backing the chunk's memory (a single owns-memory [IoBuf] of `CHUNK_SIZE`).
 *   Its reference count tracks live + cached carves: `1` (this chunk's own hold)
 *   plus one per outstanding view.
 * @property poolChunk the run/subpage bookkeeper over [backing]'s pages.
 * @property owningAllocator the [PooledAllocator] this chunk lives under, or
 *   `null` when the chunk was constructed standalone in a test. The allocator
 *   reference lets [freeRun] discover the right size-class head from a
 *   subpage handle and acquire the right locks during release. Set once at
 *   chunk creation and never mutated.
 */
class PooledChunk internal constructor(
    internal val backing: IoBuf,
    internal val poolChunk: PoolChunk,
    internal val owningAllocator: PooledAllocator? = null,
) {
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
     * Carves a new subpage of size class [sizeIdx] from this chunk and links
     * it into [head]'s pool. The caller (arena's subpage slow path) walks the
     * arena's per-class chain first and only reaches here when the chain is
     * empty or all chains are full; this method does **not** check the chain
     * itself.
     *
     * Returns the first element's handle, or [PoolChunk.NO_HANDLE] when this
     * chunk has no free run of the required size.
     */
    internal fun carveNewSubpage(sizeIdx: Int, head: PoolSubpage): Long =
        poolChunk.allocateSubpage(sizeIdx, head, this)

    /**
     * Returns the run/subpage element at [handle] to this chunk and drops one
     * reference on [backing].
     *
     * Lock acquisition (when [owningAllocator] is non-null):
     * - subpage handle: arena lock → per-class subpage head lock (nested).
     *   The head is discovered from the subpage's `headIndex` so alloc and
     *   release always lock the same sentinel.
     * - run handle: arena lock only.
     */
    internal fun freeRun(handle: Long) {
        val allocator = owningAllocator
        // Post-close release path: the allocator has already destroyed its
        // platform locks; PoolledAllocator.close()'s contract guarantees no
        // concurrent allocate at this point so the teardown branch in
        // returnToPool routes here only via close-time single-thread freeBacking.
        // Run the bookkeeping unsynchronised.
        if (allocator == null || allocator.isClosed) {
            val head: PoolSubpage? =
                if (allocator != null && PoolChunk.isSubpage(handle)) {
                    val sub = poolChunk.subpageAtRunOffset(PoolChunk.runOffset(handle))
                    sub?.headIndex?.let { allocator.subpageHeadAt(it) }
                } else {
                    null
                }
            poolChunk.free(handle, head)
            backing.release()
            liveCarves--
            return
        }
        if (PoolChunk.isSubpage(handle)) {
            val sub = checkNotNull(poolChunk.subpageAtRunOffset(PoolChunk.runOffset(handle))) {
                "no subpage at run offset ${PoolChunk.runOffset(handle)}"
            }
            val headIdx = sub.headIndex
            val head = allocator.subpageHeadAt(headIdx)
            allocator.withArenaLock {
                allocator.withSubpageHeadLock(headIdx) {
                    poolChunk.free(handle, head)
                }
            }
        } else {
            allocator.withArenaLock {
                poolChunk.free(handle, head = null)
            }
        }
        backing.release()
        liveCarves--
    }
}
