package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import io_uring.io_uring_sqe
import kotlinx.cinterop.Arena
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value

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
 * ignored.
 *
 * **Lifecycle.** The fake owns a native [Arena] for the scratch SQE, so an
 * instance that is never [dispose]d leaks that arena for the run. Ten of the
 * nineteen files that construct it used to miss the call, which is the shape
 * to avoid rather than a rule to restate: nothing checks it. detekt cannot —
 * this class is correct in isolation and the omission is in the callers, one
 * file away.
 *
 * Every call site now constructs the fake inside a helper whose `finally`
 * disposes it, alongside closing the EventLoop and buffer ring:
 *
 * ```
 * private fun withTransport(fake: FakeIoUringRing = FakeIoUringRing(), …) {
 *     …
 *     try { block(fake, el, transport) } finally {
 *         bufRing.close()
 *         el.close()
 *         fake.dispose()
 *     }
 * }
 * ```
 *
 * Copy that shape rather than constructing one directly in a `@Test`: the
 * two sites that did so are the two that had no `finally` to attach to.
 *
 * **There is no automated guard, and both obvious ones were tried.** A rule that
 * resolves the arena's owner passes this class, correctly — the omission is in a
 * caller. A test asserting a global constructed-minus-disposed balance passes
 * too: `kotlin.test` gives Native no suite-level teardown, so such a test runs at
 * an arbitrary point and sees only the leaks that happened before it. Removing a
 * `dispose()` and running the suite left it green. The shape above is the guard.
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

    /** Number of [submit] (submit-without-wait, the SQ-full drain path) calls. */
    var submitCalls: Int = 0
        private set

    /**
     * Total number of CQEs successfully drained via [nextCqe] (empty
     * queue returns do not count). Lets a companion
     * [FakeIoUringBufferRingOps] correlate an `advance` call with how
     * far the CQE drain has progressed (e.g. "buffer slot 3 was returned
     * to the ring after 5 CQEs were consumed") via the optional
     * `cqeDrainProgress` lambda the buffer-ring fake accepts.
     */
    var cqesDrainedCount: Int = 0
        private set

    /** Arguments of the most recent [setupFlags] call, or `null` if never called. */
    var lastSetupFlagsArgs: SetupFlagsArgs? = null
        private set

    /** `cqEntries` argument of the most recent [queueInit] call, or `-1` if never called. */
    var lastQueueInitCqEntries: Int = -1
        private set

    /**
     * Feature bitset written to `outFeatures` on a successful [queueInit].
     * Defaults to all bits set (so the engine's `IORING_FEAT_NODROP` assert
     * passes); set to a value without the NODROP bit to exercise the fail-fast.
     */
    var scriptedFeatures: UInt = 0xFFFFFFFFu

    /** `entries` argument of the most recent [queueInit] call, or `-1` if never called. */
    var lastQueueInitEntries: Int = -1
        private set

    /** `flags` argument of the most recent [queueInit] call. */
    var lastQueueInitFlags: UInt = 0u
        private set

    // --- Scripted failures (FIFO) ---

    private val queueInitResults = ArrayDeque<Int>()
    private val getSqeResults = ArrayDeque<Boolean>()
    private val submitResults = ArrayDeque<Int>()
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
     * Convenience alias for [enqueueCqe] documenting a `POLL_ADD` CQE.
     * The `revents` mask (e.g. `POLLRDHUP | POLLHUP | POLLERR`) is
     * delivered in the `res` field — that is the kernel ABI for poll
     * completions (`res >= 0` carries the matched event bits). Single-shot
     * `POLL_ADD` always sets `hasMore = false`.
     */
    fun scriptPollCqe(userData: ULong, revents: UInt) {
        enqueueCqe(userData, revents.toInt(), flags = 0u, hasMore = false)
    }

    /**
     * Returns the `user_data` field of the scratch SQE — the value the
     * most recent `submit*` wrote via `io_uring_sqe_set_data64`. Lets a
     * test recover the slot `user_data` for a submit path that does not
     * return its slot index (e.g. `submitSendZcCallback`).
     */
    fun lastSqeUserData(): ULong = scratchSqe.user_data

    /**
     * Returns the `poll32_events` field of the scratch SQE — the poll
     * mask the most recent `io_uring_prep_poll_add` (or its
     * `keel_prep_poll_add` wrapper) wrote. Lets a test verify that the
     * engine armed a poll watcher with the expected events (e.g.
     * `POLLRDHUP | POLLHUP | POLLERR` for peer-FIN detection while
     * `readEnabled = false`).
     *
     * The value is only meaningful immediately after a poll prep — the
     * scratch SQE's union storage is reused by later non-poll preps,
     * which may scribble over `poll32_events`. Always pair this check
     * with [lastSqeOp] `== IORING_OP_POLL_ADD` to confirm the prep was
     * a poll op.
     *
     * Same narrow-exception status as [lastSqeOp]: this reads one field
     * of the scratch SQE; other fields remain unobserved.
     */
    fun lastPollSqeMask(): UInt = scratchSqe.poll32_events

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

    /**
     * Returns the `ioprio` field of the scratch SQE — for recv ops this
     * carries the `IORING_RECV*` flag bits (e.g. `IORING_RECV_MULTISHOT`,
     * bit 1). Lets a test distinguish a multishot recv from a single-shot
     * buffer-select recv: both prep `IORING_OP_RECV`, so [lastSqeOp]
     * alone cannot tell them apart — the multishot-ness lives in
     * `ioprio`, not the opcode.
     *
     * Same narrow-exception status as [lastSqeOp] / [lastPollSqeMask]:
     * one field of the scratch SQE, only meaningful immediately after a
     * recv prep (the union storage is reused by later preps). Always
     * pair with [lastSqeOp] `== IORING_OP_RECV`.
     */
    fun lastSqeIoprio(): UShort = scratchSqe.ioprio

    /**
     * Returns the `flags` field of the scratch SQE — the `IOSQE_*` bits
     * the most recent prep wrote (e.g. `IOSQE_BUFFER_SELECT`,
     * `IOSQE_FIXED_FILE`). Lets a test distinguish a buffer-select recv
     * (kernel picks the buffer from a provided ring) from a plain recv
     * into a caller-owned buffer — both prep `IORING_OP_RECV` with the
     * multishot ioprio bit clear, so neither [lastSqeOp] nor
     * [lastSqeIoprio] can tell them apart.
     *
     * Same narrow-exception status as the other field probes: one field,
     * only meaningful immediately after the prep under test.
     */
    fun lastSqeFlags(): UByte = scratchSqe.flags

    override fun setupFlags(coopTaskrun: Boolean, singleIssuer: Boolean, deferTaskrun: Boolean): UInt {
        lastSetupFlagsArgs = SetupFlagsArgs(coopTaskrun, singleIssuer, deferTaskrun)
        var flags = 0u
        if (coopTaskrun) flags = flags or COOP_FLAG
        if (singleIssuer) flags = flags or SINGLE_FLAG
        if (deferTaskrun) flags = flags or DEFER_FLAG
        return flags
    }

    override fun queueInit(
        sqEntries: Int,
        cqEntries: Int,
        ring: CPointer<io_uring>,
        flags: UInt,
        outFeatures: CPointer<UIntVar>,
    ): Int {
        queueInitCalls++
        lastQueueInitEntries = sqEntries
        lastQueueInitCqEntries = cqEntries
        lastQueueInitFlags = flags
        val rc = queueInitResults.removeFirstOrNull() ?: 0
        if (rc == 0) outFeatures.pointed.value = scriptedFeatures
        return rc
    }

    override fun queueExit(ring: CPointer<io_uring>) {
        queueExitCalls++
    }

    override fun getSqe(ring: CPointer<io_uring>): CPointer<io_uring_sqe>? {
        getSqeCalls++
        val full = getSqeResults.removeFirstOrNull() == false
        return if (full) null else scratchSqe.ptr
    }

    override fun submit(ring: CPointer<io_uring>): Int {
        submitCalls++
        return submitResults.removeFirstOrNull() ?: 0
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
        cqesDrainedCount++
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
