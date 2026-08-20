package io.github.fukusaka.keel.native.readiness

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap

/**
 * The `iovec` arrays a gather write fills in, held once and reused.
 *
 * Per loop rather than per transport: only the loop's own thread builds a
 * gather, so one scratch serves every transport on it, and the alternative is
 * an allocation on each multi-buffer flush.
 *
 * Its own type rather than four fields on the loop, because what it holds is
 * not loop state: it is two `nativeHeap` allocations with an ownership rule of
 * their own — disown before freeing, give the first back if the second is
 * refused, release at most once — and none of that is about readiness,
 * registrations or teardown order. The loop keeps the instance and hands its
 * teardown the release.
 *
 * **Thread safety**: [ensure] is the loop thread's alone. [free] is called by
 * whichever thread tears the loop down, and what makes that safe is quiescence
 * rather than confinement — stated where the loop establishes it, since it is
 * established differently on each teardown route.
 */
@OptIn(ExperimentalForeignApi::class)
internal class WritevScratch {

    /** Base pointers for the gather's `iovec` array. */
    var bases: CPointer<CPointerVar<ByteVar>>
        private set

    /** Byte lengths (`size_t`) paired with [bases]. */
    var lens: CPointer<ULongVar>
        private set

    init {
        // Paired here rather than left as two initialisers, so a refused second
        // allocation gives the first one back. The engines accept that failure
        // in their own initialisers, on the grounds that a `nativeHeap`
        // allocation of a few dozen bytes failing is not a condition the
        // process continues past — but that stance is stated about theirs, and
        // these are not theirs. A constructor that throws leaves no reference
        // for anyone to clean up: the engines' recovery is a `try` in their own
        // `init`, which a throw from here never reaches.
        val allocatedBases: CPointer<CPointerVar<ByteVar>> = nativeHeap.allocArray(INITIAL_CAPACITY)
        lens = try {
            nativeHeap.allocArray(INITIAL_CAPACITY)
        } catch (allocationFailure: Throwable) {
            nativeHeap.free(allocatedBases)
            throw allocationFailure
        }
        bases = allocatedBases
    }

    private var capacity = INITIAL_CAPACITY

    /**
     * Whether [bases] / [lens] are ours to free.
     *
     * A plain `var`, and not because the loop thread owns it — the thread that
     * frees is whichever one is tearing the loop down: the caller of `close()`,
     * or the constructing thread when construction fails. The engines' CAS on
     * `close()` settles which caller reaches the release, but a single caller
     * is not the same as no concurrency. What supplies that is quiescence,
     * established differently on each route: `pthread_join` where a thread was
     * started; the loop reporting itself stopped where one was not (and where
     * the terminal sequence throws, the loop publishes quiescence from a
     * `finally`, so the `catch` that releases is covered too); and nothing to
     * be concurrent with when construction fails or when a test double closes.
     *
     * A stopped-loop teardown does reach a transport on another thread while
     * this runs, which is why it does not flush — see the transport's
     * `teardownAfterLoopStopped`. So a release never runs beside a gather.
     */
    var owned = true
        private set

    /**
     * Grows [bases] / [lens] (1.5x, at least [n]) so a gather of [n] buffers
     * fits. Called on the loop's thread only.
     */
    fun ensure(n: Int) {
        if (n <= capacity) return
        val grown = maxOf(capacity + (capacity shr 1), n)
        // Give up the old scratch before freeing it, not after: an allocation
        // that throws below would otherwise leave the loop still claiming
        // pointers it no longer holds, at a capacity that says they are big
        // enough to gather through. Disowned first, the failure leaves nothing
        // to double-free and nothing a later gather can reach.
        if (owned) {
            owned = false
            capacity = 0
            nativeHeap.free(bases)
            nativeHeap.free(lens)
        }
        // Into locals, and the second allocation guarded: a throw between the
        // two would otherwise strand the first array with no owner — this
        // method skips its free block while disowned, and [free] returns early
        // for the same reason.
        val grownBases: CPointer<CPointerVar<ByteVar>> = nativeHeap.allocArray(grown)
        val grownLens: CPointer<ULongVar> = try {
            nativeHeap.allocArray(grown)
        } catch (allocationFailure: Throwable) {
            nativeHeap.free(grownBases)
            throw allocationFailure
        }
        bases = grownBases
        lens = grownLens
        capacity = grown
        owned = true
    }

    /**
     * Releases the scratch. Called from each engine's teardown.
     *
     * Idempotent: the two teardown paths that reach it are mutually exclusive
     * today, but `close()` is a public obligation on every loop — including the
     * test doubles, which own a scratch without owning a thread — and a second
     * `nativeHeap.free` of the same pointer is not a no-op.
     */
    fun free() {
        if (!owned) return
        owned = false
        // Capacity goes with the memory. Left at its old value, the early
        // return in [ensure] would hand a gather the pointers this just freed
        // for every request that fits the capacity we no longer have.
        capacity = 0
        nativeHeap.free(bases)
        nativeHeap.free(lens)
    }

    private companion object {
        /**
         * Initial gather capacity; grows on demand via [ensure].
         *
         * What both engines used before they shared this. It briefly became 16
         * when the scratch moved out of them, with nothing recorded and nothing
         * measured — and the neighbouring engine documents 8 as covering the
         * same `pendingWrites` depth, so the two would have disagreed about one
         * deque. Changing it is a separate question from moving it.
         */
        const val INITIAL_CAPACITY = 8
    }
}
