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

    /** The primary IoBuf view — the unit a pool recycles. Set when the view is constructed. */
    internal var view: IoBuf? = null

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

    /** Restores [refCount] to 1 for pool reuse. */
    internal fun resetForReuse() {
        refCount = 1
    }
}
