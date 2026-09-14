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
 * **Distinction from [IoBufOwner]**: [Releasable] is implemented by the
 * *resource itself* — it is the holder's handle for relinquishing ownership.
 * [IoBufOwner] is the *strategy object* carried by an [IoBuf] that decides
 * what happens when the refcount reaches zero (free heap memory, return
 * to pool, release a parent slice, …). The two roles are separate:
 * `IoBuf.release()` decrements the count ([Releasable] side) and, only at
 * zero, delegates to the buffer's `owner.release(buf)` ([IoBufOwner] side).
 *
 * @see IoBuf for the primary implementation
 * @see IoBufOwner for the complementary release-strategy interface
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

    /**
     * Whether [other] owns the same resource as this — whether releasing one
     * of the two gives back what the other still holds.
     *
     * Code that releases on someone else's behalf asks it before doing so: a
     * handler that passed on an object sharing ownership with the one it
     * received has handed that ownership on, and releasing the received one
     * too would free what the next handler holds.
     *
     * The default is identity. A derived object that takes its own reference
     * — a slice retains its source — does not share ownership in this sense;
     * a type whose copies share one resource without counting it overrides
     * this.
     */
    fun sharesOwnershipWith(other: Any): Boolean = other === this
}
