package io.github.fukusaka.keel.buf

/**
 * The lifetime unit of a fixed-size raw memory region.
 *
 * A [Segment] pairs a [RawSegmentBacking] (the opaque platform memory)
 * with the [capacity] of that region, and carries the [refCount] +
 * [owner] that govern when the backing memory is reclaimed. The platform
 * [IoBuf] implementations are *views* over a [Segment]: they read the
 * platform base out of [backing] once at construction, cache it, keep
 * their own `readerIndex` / `writerIndex`, and delegate [retain] /
 * [release] to the segment.
 *
 * The [capacity] is its own field rather than a constant because a
 * "huge" segment (a request larger than the pooled size class) is
 * larger than the standard pooled allocator's segment size.
 *
 * **Pool unit**: pooled allocators ([SlabAllocator] /
 * [PooledDirectAllocator]) recycle [Segment]s (the lifetime unit), not
 * views. A pool retains the [Segment]; on pop the cached primary [view]
 * is reset (`readerIndex` / `writerIndex` to 0, [refCount] to 1) and
 * returned to the caller. [nextLink] is the intrusive freelist link.
 *
 * @property backing  The opaque platform memory region.
 * @property capacity Size of the region in bytes.
 */
class Segment internal constructor(
    internal val backing: RawSegmentBacking,
    internal val capacity: Int,
) {
    /** Non-atomic reference count (single-EventLoop ownership invariant). */
    internal var refCount: Int = 1

    /** Strategy invoked when [refCount] reaches 0. */
    internal var owner: SegmentOwner = HeapOwner

    /**
     * Cached primary [IoBuf] view over this segment.
     *
     * Set when the primary view is constructed and re-used for the
     * lifetime of the segment, including across pool recycles — the
     * segment and its primary view are recycled as a pair.
     */
    internal var view: IoBuf? = null

    /**
     * Intrusive freelist link for lock-free pool freelists (Treiber stack)
     * and ArrayDeque-based freelists.
     *
     * Non-null only while this segment resides in a pool's freelist.
     * Allows pools to chain segments without wrapper-node allocations.
     */
    internal var nextLink: Segment? = null

    internal fun retain() {
        check(refCount > 0) { "Cannot retain a released segment" }
        refCount++
    }

    /** Decrements [refCount]; at zero invokes [owner]. Returns true iff this call released the segment. */
    internal fun release(): Boolean {
        check(refCount > 0) { "Segment already released" }
        if (--refCount == 0) {
            owner.release(this)
            return true
        }
        return false
    }

    /**
     * Restores this segment to a fresh-from-allocator state for pool
     * reuse: [refCount] back to 1 and [nextLink] cleared. The primary
     * [view]'s `readerIndex` / `writerIndex` are reset separately by the
     * allocator on pop.
     */
    internal fun resetForReuse() {
        refCount = 1
        nextLink = null
    }
}
