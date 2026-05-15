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
 * **Scope note**: this interface currently covers the ring lifecycle
 * and SQE acquisition ([getSqe]). The `io_uring_prep_*` SQE field
 * writers and the CQE drain API (`io_uring_submit_and_wait` /
 * `io_uring_peek_cqe` / ...) operate on the same `io_uring` struct and
 * are a natural extension; the CQE side is deferred to a follow-up PR
 * whose fake has to emulate kernel CQE delivery — a meaningfully larger
 * design than the per-call outcomes this seam needs. The `prep_*`
 * writers stay as direct calls on the [getSqe]-returned pointer (the
 * fake hands back a scratch `io_uring_sqe`, so they write harmless
 * memory) — the "Option A" approach that keeps this migration small.
 *
 * **Convention**: [queueInit] returns the native liburing encoding
 * directly — `0` on success, negative `-errno` on failure.
 *
 * Thread safety: the production implementation is stateless and safe to
 * share; a fake is single-threaded (driven by the test thread).
 */
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
}
