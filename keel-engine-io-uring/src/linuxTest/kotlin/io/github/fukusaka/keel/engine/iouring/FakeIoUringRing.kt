package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import io_uring.io_uring_sqe
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr

/**
 * In-memory [IoUringRing] that lets tests script the ring setup outcome
 * and SQE acquisition, and inspect the lifecycle calls. Single-threaded —
 * only safe to drive from the test thread.
 *
 * [queueInit] consults a FIFO of scripted failures; when the queue is
 * empty it succeeds. [setupFlags] returns a deterministic bit encoding
 * ([COOP_FLAG] / [SINGLE_FLAG] / [DEFER_FLAG]) — distinct from the real
 * `IORING_SETUP_*` constants — so a test can assert which capabilities
 * were folded in. [getSqe] returns a single reusable scratch
 * `io_uring_sqe` (so the caller's `io_uring_prep_*` writes land on valid
 * memory) unless a `null` (SQ-ring-full) outcome is scripted.
 *
 * The native `ring` argument is accepted to satisfy the interface but
 * ignored. The fake owns a native [Arena] for the scratch SQE; the test
 * must call [dispose] when finished.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeIoUringRing : IoUringRing {

    private val arena = Arena()

    // Single reusable scratch SQE. Option A: the engine's io_uring_prep_*
    // calls write into this struct; the fake never inspects it.
    private val scratchSqe = arena.alloc<io_uring_sqe>()

    /** The boolean arguments of one [setupFlags] invocation. */
    data class SetupFlagsArgs(val coopTaskrun: Boolean, val singleIssuer: Boolean, val deferTaskrun: Boolean)

    // --- Call tracking ---

    var queueInitCalls: Int = 0
        private set
    var queueExitCalls: Int = 0
        private set
    var getSqeCalls: Int = 0
        private set

    /** Arguments of the most recent [setupFlags] call, or `null` if never called. */
    var lastSetupFlagsArgs: SetupFlagsArgs? = null
        private set

    /** `entries` argument of the most recent [queueInit] call, or `-1` if never called. */
    var lastQueueInitEntries: Int = -1
        private set

    /** `flags` argument of the most recent [queueInit] call. */
    var lastQueueInitFlags: UInt = 0u
        private set

    // --- Scripted failures (FIFO) ---

    private val queueInitResults = ArrayDeque<Int>()
    private val getSqeResults = ArrayDeque<Boolean>()

    /** Scripts the next [queueInit] call to fail with [errno] (encoded `-errno`). */
    fun scriptQueueInitFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        queueInitResults.addLast(-errno)
    }

    /**
     * Scripts the next [getSqe] call to return `null` — the SQ ring is
     * full. Subsequent unscripted calls return the scratch SQE again.
     */
    fun scriptSqRingFull() {
        getSqeResults.addLast(false)
    }

    override fun setupFlags(coopTaskrun: Boolean, singleIssuer: Boolean, deferTaskrun: Boolean): UInt {
        lastSetupFlagsArgs = SetupFlagsArgs(coopTaskrun, singleIssuer, deferTaskrun)
        var flags = 0u
        if (coopTaskrun) flags = flags or COOP_FLAG
        if (singleIssuer) flags = flags or SINGLE_FLAG
        if (deferTaskrun) flags = flags or DEFER_FLAG
        return flags
    }

    override fun queueInit(entries: Int, ring: CPointer<io_uring>, flags: UInt): Int {
        queueInitCalls++
        lastQueueInitEntries = entries
        lastQueueInitFlags = flags
        return queueInitResults.removeFirstOrNull() ?: 0
    }

    override fun queueExit(ring: CPointer<io_uring>) {
        queueExitCalls++
    }

    override fun getSqe(ring: CPointer<io_uring>): CPointer<io_uring_sqe>? {
        getSqeCalls++
        val full = getSqeResults.removeFirstOrNull() == false
        return if (full) null else scratchSqe.ptr
    }

    /** Frees the native [Arena] backing the scratch SQE. Call once per test. */
    fun dispose() {
        arena.clear()
    }

    companion object {
        /** Bit set by [setupFlags] when `coopTaskrun` is enabled. */
        const val COOP_FLAG: UInt = 1u

        /** Bit set by [setupFlags] when `singleIssuer` is enabled. */
        const val SINGLE_FLAG: UInt = 2u

        /** Bit set by [setupFlags] when `deferTaskrun` is enabled. */
        const val DEFER_FLAG: UInt = 4u
    }
}
