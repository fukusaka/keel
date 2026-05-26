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
 * - the non-atomic [refCount] + [owner] dispatch underlying
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
 * **Reference counting**: non-atomic — all access must happen on the
 * single EventLoop thread that owns the buffer (see [IoBuf]'s thread
 * safety contract). Cross-thread access is a contract violation, not
 * guarded by atomics.
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
     * Single-EventLoop ownership is the assumed contract for kqueue /
     * epoll / io_uring / nio / netty / JS engines (each has one pthread
     * per worker and never migrates a connection's IoBuf calls off it).
     * GCD-backed engines (NWConnection) serialise blocks per-connection
     * but migrate them across OS threads, so the refcount must carry
     * explicit memory-order guarantees or the optimiser may reorder /
     * cache reads across thread boundaries and observe a stale value.
     * The race manifested as a sporadic SIGABRT
     * `Buffer already released` inside `HttpHeaders.resetForReuse()`
     * on `server-http × nwconnection` HTTPS sweeps at ~7 % per-run rate
     * (K56). Atomic adds the missing memory barriers without changing
     * the single-owner contract on the well-behaved engines.
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
        val before = refCount.fetchAndAdd(1)
        check(before > 0) { "Cannot retain a released buffer" }
        return this
    }

    final override fun release(): Boolean {
        val before = refCount.fetchAndAdd(-1)
        check(before > 0) { "Buffer already released" }
        if (before == 1) {
            owner.release(this)
            return true
        }
        return false
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
     * intentionally skipped — see [IoBuf.close]. Idempotent.
     */
    final override fun close() {
        freeBacking()
    }
}
