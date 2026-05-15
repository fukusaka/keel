package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * In-memory [IoUringRing] that lets tests script the ring setup outcome
 * and inspect the lifecycle calls. Single-threaded — only safe to drive
 * from the test thread.
 *
 * [queueInit] consults a FIFO of scripted failures; when the queue is
 * empty it succeeds. [setupFlags] returns a deterministic bit encoding
 * ([COOP_FLAG] / [SINGLE_FLAG] / [DEFER_FLAG]) — distinct from the real
 * `IORING_SETUP_*` constants — so a test can assert which capabilities
 * were folded in. The native `ring` argument is accepted to satisfy the
 * interface but ignored.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeIoUringRing : IoUringRing {

    /** The boolean arguments of one [setupFlags] invocation. */
    data class SetupFlagsArgs(val coopTaskrun: Boolean, val singleIssuer: Boolean, val deferTaskrun: Boolean)

    // --- Call tracking ---

    var queueInitCalls: Int = 0
        private set
    var queueExitCalls: Int = 0
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

    /** Scripts the next [queueInit] call to fail with [errno] (encoded `-errno`). */
    fun scriptQueueInitFailure(errno: Int) {
        require(errno > 0) { "errno must be positive, got $errno" }
        queueInitResults.addLast(-errno)
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

    companion object {
        /** Bit set by [setupFlags] when `coopTaskrun` is enabled. */
        const val COOP_FLAG: UInt = 1u

        /** Bit set by [setupFlags] when `singleIssuer` is enabled. */
        const val SINGLE_FLAG: UInt = 2u

        /** Bit set by [setupFlags] when `deferTaskrun` is enabled. */
        const val DEFER_FLAG: UInt = 4u
    }
}
