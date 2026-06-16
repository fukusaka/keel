@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

import kotlin.concurrent.atomics.AtomicInt

/**
 * Common skeleton for the three platform [IoBuf] implementations
 * ([NativeIoBuf] / [DirectIoBuf] / [TypedArrayIoBuf]).
 *
 * Lifts the parts that have no platform-specific component out of the
 * concrete classes:
 *
 * - the index pair ([readerIndex] / [writerIndex] + derived
 *   [readableBytes] / [writableBytes]),
 * - the atomic [refCount] + [owner] dispatch underlying
 *   [retain] / [release],
 * - the [close] escape hatch routing through [freeBacking].
 *
 * Concrete subclasses only need to:
 *
 * - declare the platform `base` pointer / buffer / array (read once at
 *   construction, used directly on the hot per-byte path so a virtual
 *   dispatch is avoided),
 * - implement the per-byte / bulk read & write primitives,
 * - implement [freeBacking] (`nativeHeap.free` on Native owned memory,
 *   no-op on JVM / JS GC-managed memory and on external wraps),
 * - declare their own pool-freelist `nextLink` field (typed as the
 *   concrete class so the pool stays statically typed),
 * - override [resetForReuse] if they have additional pool-reuse state
 *   to reset (the concrete `nextLink`, ByteBuffer position/limit, …).
 *
 * **Reference counting**: atomic ([AtomicInt] + `fetchAndAdd`) so
 * lifecycle ([retain] / [release] / [close]) is thread-safe across every
 * keel buffer implementation — see [IoBuf]'s thread-safety contract.
 * Content access (read / write / index updates) remains single-thread
 * by contract; the atomic refcount carries the memory-order guarantees
 * lifecycle needs without lifting the single-thread restriction on
 * content. The original motivation was the GCD-backed
 * NWConnection engine (serial-per-connection but migrating across OS
 * worker pthreads), which exhibited the
 * `Buffer already released` SIGABRT inside `HttpHeaders.resetForReuse()`
 * on `server-http × nwconnection` HTTPS sweeps at ~7 % per-run rate
 * (the GCD cross-worker refcount race) before the field became atomic;
 * the contract is now uniform across all engines.
 *
 * **Dispatch**: [retain] / [release] / [close] / [clear] are `final`
 * so JVM / Native compilers can resolve them statically through the
 * concrete subtype. Per-byte read / write methods are abstract and
 * called on the static concrete type at every engine / codec call
 * site, so no virtual dispatch is introduced on the hot path.
 *
 * **Visibility**: `public abstract class` with `internal constructor`.
 * Public because the three platform IoBufs are `public class`
 * (engines need to construct them). The `internal` constructor +
 * `internal abstract fun freeBacking()` (inherited from
 * [PoolableIoBuf]) keep external subclassing impractical without
 * internal access; engine-direct IoBufs that implement [IoBuf]
 * directly (without extending [AbstractIoBuf]) are unaffected.
 *
 * @param capacity Capacity of the window this buffer addresses. For a
 *   primary view this equals the backing allocation size; for a slice
 *   it is the windowed sub-range length.
 */
abstract class AbstractIoBuf internal constructor(
    final override val capacity: Int,
) : IoBuf, PoolableIoBuf {

    final override var readerIndex: Int = 0
    final override var writerIndex: Int = 0

    final override val readableBytes: Int get() = writerIndex - readerIndex
    final override val writableBytes: Int get() = capacity - writerIndex

    /**
     * Atomic reference count.
     *
     * Maintains the cross-thread lifecycle invariant from the [IoBuf]
     * contract: lifecycle ([retain] / [release] / [close]) is thread-safe
     * regardless of which engine produced the buffer. Content access
     * (read / write / index updates) remains single-thread by contract,
     * so this is the only atomic field on the buffer's hot path; the
     * fetch-and-add cost (~5-10 ns on x86_64) only fires on lifecycle
     * transitions, not per byte / per read / per write.
     *
     * The historical trigger was the GCD-backed NWConnection engine
     * (serialises blocks per-connection but migrates them across OS
     * worker pthreads), where a non-atomic refcount let the optimiser
     * reorder / cache the field across thread boundaries — observed as
     * `Buffer already released` SIGABRT inside
     * `HttpHeaders.resetForReuse()` on `server-http × nwconnection`
     * HTTPS sweeps at ~7 % per-run rate. The atomic field eliminated
     * that race; making it the uniform contract across every engine
     * eliminates the corresponding contract-boundary trap when an
     * allocator hands a buffer to a thread that wasn't its original
     * owner (e.g. an off-EL `Channel.allocator` consumer).
     */
    private val refCount = AtomicInt(1)

    /**
     * Release-path strategy invoked at [refCount] zero. Mutable so
     * decorators ([TrackingAllocator] / [LeakDetectingAllocator]) can
     * intercept the release path without changing the public [IoBuf]
     * surface — see [PoolableIoBuf].
     */
    final override var owner: IoBufOwner = HeapOwner

    final override fun retain(): IoBuf {
        // CAS-loop instead of fetchAndAdd-then-check so a retain that loses to
        // a concurrent release (or close) does not leave a stale increment in
        // [refCount] after the check throws. Each iteration:
        //
        //   1. snapshot the current count
        //   2. throw if the buffer is already released / closed (count == 0)
        //   3. CAS-bump to count + 1; retry on contention
        //
        // Under low contention the loop runs once and costs roughly the same
        // as a fetchAndAdd; only a contended buffer (off-EL retain races) does
        // any retry work. Importantly, a thread whose check fails never
        // perturbs [refCount] — the bug-prone fetchAndAdd-first design would
        // increment first, throw second, and leave count == 1 even though the
        // buffer was released, corrupting any subsequent release / retain.
        while (true) {
            val cur = refCount.load()
            check(cur > 0) { "Cannot retain a released buffer" }
            if (refCount.compareAndSet(cur, cur + 1)) return this
        }
    }

    final override fun release(): Boolean {
        // CAS-loop, same rationale as [retain]: a release that loses to a
        // concurrent release / close must not leave a stale decrement, and the
        // owner.release(this) dispatch must run exactly once — only on the
        // thread whose CAS drove count from 1 to 0.
        while (true) {
            val cur = refCount.load()
            check(cur > 0) { "Buffer already released" }
            if (refCount.compareAndSet(cur, cur - 1)) {
                if (cur == 1) {
                    owner.release(this)
                    return true
                }
                return false
            }
        }
    }

    /**
     * Resets indices to 0; does not zero the underlying memory.
     * Subclasses (e.g. [DirectIoBuf] resetting ByteBuffer position /
     * limit) may override and call `super.clear()`.
     */
    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    /**
     * Restores this buffer to a fresh-from-allocator state for pool
     * reuse: indices to 0, [refCount] to 1. Subclasses with
     * additional pool-reuse state (concrete `nextLink`, ByteBuffer
     * position / limit, …) override and call `super.resetForReuse()`.
     * Invoked by the pool allocator on pop().
     */
    internal open fun resetForReuse() {
        readerIndex = 0
        writerIndex = 0
        refCount.store(1)
    }

    /**
     * Teardown escape hatch: frees the backing without invoking
     * [owner]. Pool returns and external-resource unpins are
     * intentionally skipped — see [IoBuf.close].
     *
     * **Concurrency.** Idempotent and thread-safe with concurrent
     * [retain] / [release] / [close] on the same buffer: the
     * `compareAndSet` to force [refCount] to zero runs at most once
     * per buffer (winning thread sets it from a positive value to
     * zero), so [freeBacking] is invoked exactly once and concurrent
     * retain / release on the lifecycle path observe the released
     * state on their next CAS attempt and throw `IllegalStateException`.
     *
     * **Caller's responsibility.** Concurrent content access (any
     * `read*` / `write*` / `getByte` / index update) from another
     * thread that still believed it held a reference will read or
     * write the freed backing — that is use-after-free at the
     * memory layer and the lifecycle CAS cannot prevent it. This
     * matches the `IoBuf` contract: content access is
     * single-thread-at-a-time and the [close] caller must guarantee
     * no other thread is mid-access. [close] is documented as an
     * "engine shutdown / emergency teardown" escape hatch precisely
     * because the calling context is expected to have already
     * quiesced the buffer's content users.
     */
    final override fun close() {
        while (true) {
            val cur = refCount.load()
            if (cur == 0) return // Already closed / released — idempotent no-op.
            if (refCount.compareAndSet(cur, 0)) {
                freeBacking()
                return
            }
        }
    }
}
