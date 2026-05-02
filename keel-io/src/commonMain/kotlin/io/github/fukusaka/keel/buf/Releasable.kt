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
 * **Distinction from [IoBufMemoryOwner]**: [Releasable] is implemented by the
 * *resource itself* — it is the holder's handle for relinquishing ownership.
 * [IoBufMemoryOwner] is the *strategy object* embedded inside an [IoBuf] that
 * decides what happens when the refcount reaches zero (free heap memory, return
 * to pool, release a kernel-registered slot, …). The two roles are separate:
 * `IoBuf.release()` decrements the count ([Releasable] side) and, only at
 * zero, delegates to `IoBuf.memoryOwner.release(buf)` ([IoBufMemoryOwner] side).
 *
 * @see IoBuf for the primary implementation
 * @see IoBufMemoryOwner for the complementary release-strategy interface
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
