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
 * [submitAndWait] returns a scripted value (default `0`); [nextCqe]
 * drains a FIFO of CQEs enqueued via [enqueueCqe] — manual scripting
 * only, no auto-generation of CQEs from submitted SQEs.
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
    var submitAndWaitCalls: Int = 0
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
    private val submitAndWaitResults = ArrayDeque<Int>()

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

    /**
     * Scripts the next [submitAndWait] return value. Use `0` (or any
     * non-negative count) for success and a negative `-errno`
     * (`-EINTR` to drive the retry path, any other for a fatal exit)
     * for failure. Unscripted calls return `0`.
     */
    fun scriptSubmitAndWait(ret: Int) {
        submitAndWaitResults.addLast(ret)
    }

    // --- Scripted CQEs (FIFO) ---

    private data class ScriptedCqe(val userData: ULong, val res: Int, val flags: UInt, val hasMore: Boolean)

    private val cqeQueue = ArrayDeque<ScriptedCqe>()

    /**
     * Enqueues a CQE for [nextCqe] to drain. The drain loop in
     * `runIteration` consumes the queue in FIFO order until it is empty.
     */
    fun enqueueCqe(userData: ULong, res: Int, flags: UInt = 0u, hasMore: Boolean = false) {
        cqeQueue.addLast(ScriptedCqe(userData, res, flags, hasMore))
    }

    /**
     * Returns the `user_data` field of the scratch SQE — the value the
     * most recent `submit*` wrote via `io_uring_sqe_set_data64`. Lets a
     * test recover the slot `user_data` for a submit path that does not
     * return its slot index (e.g. `submitSendZcCallback`).
     */
    fun lastSqeUserData(): ULong = scratchSqe.user_data

    /**
     * Returns the `opcode` field of the scratch SQE — the value the most
     * recent `io_uring_prep_*` call wrote when preparing the submission.
     * Lets a test distinguish multishot recv vs multishot accept vs poll
     * (and any other op kind), which the slot/user_data does not capture.
     *
     * **Narrow exception to the Option-A invariant** ([scratchSqe] is
     * otherwise treated as a black box that the fake never inspects).
     * Reading the single byte `opcode` field is allowed because (a) it
     * is the only field that uniquely identifies the operation kind,
     * (b) every `io_uring_prep_*` wrapper writes it as its first step
     * (`sqe->opcode = IORING_OP_*`), and (c) the test contract still
     * does not depend on any other SQE field. Other fields (`fd`,
     * `addr`, `len`, `off`, flags, etc.) remain unobserved.
     *
     * The scratch SQE is reused across submissions, so this returns the
     * opcode of the **most recent** prep call — sufficient for the
     * common case of one prep per submit. Tests that batch multiple
     * preps per submit cycle must inspect immediately after each prep.
     */
    fun lastSqeOp(): UByte = scratchSqe.opcode

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

    override fun submitAndWait(ring: CPointer<io_uring>, minComplete: Int): Int {
        submitAndWaitCalls++
        return submitAndWaitResults.removeFirstOrNull() ?: 0
    }

    /** Records the last relative timeout passed to [submitAndWaitTimeout] (seconds, nanos). */
    var lastTimeoutArgs: Pair<Long, Long>? = null

    override fun submitAndWaitTimeout(ring: CPointer<io_uring>, minComplete: Int, seconds: Long, nanos: Long): Int {
        submitAndWaitCalls++
        lastTimeoutArgs = seconds to nanos
        return submitAndWaitResults.removeFirstOrNull() ?: 0
    }

    override fun nextCqe(ring: CPointer<io_uring>, out: Cqe): Boolean {
        val c = cqeQueue.removeFirstOrNull() ?: return false
        out.userData = c.userData
        out.res = c.res
        out.flags = c.flags
        out.hasMore = c.hasMore
        return true
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
