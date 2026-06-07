package io.github.fukusaka.keel.buf

/**
 * Constructs a [Freelist] for one size class.
 *
 * The seam [PooledAllocator] uses to let a caller swap the per-size-class
 * concurrency strategy without subclassing. A constructor reference of any
 * `Freelist` implementation with a matching shape is a valid factory —
 * e.g. `::MutexFreelist` selects the blocking-lock variant on either
 * platform — and bespoke implementations work the same way.
 *
 * Modelled as a `fun interface` rather than `(Int) -> Freelist` so the role
 * carries a name at call sites and KDoc, and so future seam expansion (a
 * second parameter, a default method) does not break the call sites that
 * pass a constructor reference today.
 */
fun interface FreelistFactory {
    /**
     * Creates a [Freelist] sized to hold at most [maxSlots] buffers. The
     * allocator decides the slot count from its per-class budget and passes
     * it in here.
     */
    fun create(maxSlots: Int): Freelist
}
