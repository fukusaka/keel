package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import io_uring.io_uring_sqe
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Semantic abstraction over the io_uring ring itself — its lifecycle
 * (`io_uring_queue_init` / `io_uring_queue_exit`) and the `IORING_SETUP_*`
 * flag assembly — used by [IoUringEventLoop]. Introduced so the ring
 * setup failure branch is reachable from seam tests without a real Linux
 * kernel.
 *
 * Part of the io_uring native API seam effort (sibling of
 * `IoUringSyscallOps` and the register-class `*Ops` seams).
 *
 * **Scope note**: this interface covers the ring lifecycle, SQE
 * acquisition ([getSqe]) and the CQE drain ([submitAndWait] / [nextCqe]).
 * The `io_uring_prep_*` SQE field writers stay as direct calls on the
 * [getSqe]-returned pointer (the fake hands back a scratch `io_uring_sqe`,
 * so they write harmless memory) — the "Option A" approach that keeps
 * the SQE migration small.
 *
 * **Convention**: [queueInit] returns the native liburing encoding
 * directly — `0` on success, negative `-errno` on failure.
 *
 * Thread safety: the production implementation is stateless and safe to
 * share; a fake is single-threaded (driven by the test thread).
 */
/**
 * Mutable carrier for one drained completion queue entry. Reused across
 * drain iterations — [IoUringRing.nextCqe] fills it in place — so the
 * loop allocates nothing per CQE, the same pattern `EpEvent` uses for
 * `epoll_wait`. A plain `class` with `var` fields, not a `data class`:
 * reuse semantics conflict with the value-type equality a `data class`
 * implies.
 */
internal class Cqe {
    /** SQE `user_data` echoed back by the kernel — a slot index plus base, or a reserved token. */
    var userData: ULong = 0u

    /** Operation result: bytes transferred (`>= 0`) or a negative `-errno`. */
    var res: Int = 0

    /** CQE flag bitmask (e.g. `IORING_CQE_F_BUFFER`, `IORING_CQE_F_MORE`). */
    var flags: UInt = 0u

    /**
     * `true` when `IORING_CQE_F_MORE` is set — the originating SQE is
     * multishot and will produce further CQEs, so its slot must be kept.
     */
    var hasMore: Boolean = false
}

@OptIn(ExperimentalForeignApi::class)
internal interface IoUringRing {

    /**
     * Assembles the `IORING_SETUP_*` flag word from the enabled
     * capabilities.
     *
     * `DEFER_TASKRUN` requires `SINGLE_ISSUER` per the kernel; this
     * method does not enforce that — it relies on `IoUringCapabilities`
     * detection keeping the two consistent, and an explicit user
     * override is intentional.
     */
    fun setupFlags(coopTaskrun: Boolean, singleIssuer: Boolean, deferTaskrun: Boolean): UInt

    /**
     * Creates and memory-maps the io_uring ring of [entries] SQE slots
     * into [ring] via `io_uring_queue_init`, applying [flags].
     *
     * @return `0` on success; negative `-errno` on failure (e.g. `-EPERM`
     *   when `io_uring` is restricted, `-ENOMEM` under memory pressure,
     *   `-EINVAL` for an unsupported flag combination).
     */
    fun queueInit(entries: Int, ring: CPointer<io_uring>, flags: UInt): Int

    /**
     * Unmaps and tears down the io_uring ring via `io_uring_queue_exit`.
     * The caller must only invoke this on a ring that [queueInit]
     * successfully initialised.
     */
    fun queueExit(ring: CPointer<io_uring>)

    /**
     * Acquires the next free submission queue entry from [ring] via
     * `io_uring_get_sqe`. The caller fills it with `io_uring_prep_*` and
     * `io_uring_sqe_set_data64`.
     *
     * @return a pointer to the SQE, or `null` when the SQ ring is full
     *   (all entries are in flight and not yet consumed by
     *   `io_uring_submit_and_wait`). Callers either fail fast or defer
     *   the submission.
     */
    fun getSqe(ring: CPointer<io_uring>): CPointer<io_uring_sqe>?

    /**
     * Submits all queued SQEs and blocks until at least [minComplete]
     * CQEs are available, in a single `io_uring_enter` syscall, via
     * `io_uring_submit_and_wait`.
     *
     * @return the number of SQEs submitted (`>= 0`) on success; a
     *   negative `-errno` on failure. `-EINTR` means a signal
     *   interrupted the wait and the caller should retry; any other
     *   negative value is fatal.
     */
    fun submitAndWait(ring: CPointer<io_uring>, minComplete: Int): Int

    /**
     * Like [submitAndWait], but bounds the wait by a relative timeout of
     * [seconds] + [nanos] from now (`io_uring_submit_and_wait_timeout`). Backs
     * the deadline-driven idle-timeout wait: the loop computes the next
     * connection deadline and passes it here so an idle connection's timer can
     * fire without an external wakeup. On modern kernels the timeout travels via
     * `io_uring_enter`'s GETEVENTS arg (no timeout SQE consumed, no sentinel CQE
     * surfaced), so the caller drains real CQEs with [nextCqe] exactly as after
     * [submitAndWait].
     *
     * @return the number of SQEs submitted (`>= 0`) on success; `-ETIME` if the
     *   timeout elapsed with no completion (non-fatal — the caller services due
     *   deadlines and continues); `-EINTR` on signal interruption (retry); any
     *   other negative `-errno` is fatal.
     */
    fun submitAndWaitTimeout(ring: CPointer<io_uring>, minComplete: Int, seconds: Long, nanos: Long): Int

    /**
     * Drains the next available completion queue entry into [out] and
     * marks it consumed (`io_uring_peek_cqe` + `io_uring_cqe_seen`).
     * [out] is a caller-owned carrier reused across calls so the drain
     * loop allocates nothing.
     *
     * @return `true` if a CQE was drained — [out] now holds its
     *   `user_data` / `res` / `flags` / `hasMore`; `false` when the
     *   completion queue is empty.
     */
    fun nextCqe(ring: CPointer<io_uring>, out: Cqe): Boolean
}
