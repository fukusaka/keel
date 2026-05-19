package io.github.fukusaka.keel.buf

/**
 * A resource that participates in reference-counted ownership.
 *
 * Callers signal that they no longer need the resource by calling [release].
 * When the internal reference count reaches zero the underlying allocation
 * is freed. Implementations that do not use reference counting (e.g.
 * singleton sentinels like [EmptyIoBuf]) may treat [release] as a no-op and
 * always return `false`.
 *
 * **Distinction from [SegmentOwner]**: [Releasable] is implemented by the
 * *resource itself* — it is the holder's handle for relinquishing ownership.
 * [SegmentOwner] is the *strategy object* carried by a [Segment] that
 * decides what happens when the refcount reaches zero (free heap memory, return
 * to pool, release a parent slice, …). The two roles are separate:
 * `IoBuf.release()` decrements the count ([Releasable] side) and, only at
 * zero, delegates to `Segment.owner.release(segment)` ([SegmentOwner] side).
 *
 * @see IoBuf for the primary implementation
 * @see SegmentOwner for the complementary release-strategy interface
 */
interface Releasable {

    /**
     * Decrements the reference count.
     *
     * Returns `true` if the resource was freed (count reached zero),
     * `false` if other holders remain.
     *
     * @throws IllegalStateException if the resource has already been fully released.
     */
    fun release(): Boolean
}
