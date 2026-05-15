package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import io_uring.io_uring_cqe_get_data64
import io_uring.io_uring_cqe_seen
import io_uring.io_uring_get_sqe
import io_uring.io_uring_queue_exit
import io_uring.io_uring_queue_init
import io_uring.io_uring_sqe
import io_uring.io_uring_submit_and_wait
import io_uring.keel_cqe_has_more
import io_uring.keel_peek_cqe
import io_uring.keel_setup_coop_taskrun
import io_uring.keel_setup_defer_taskrun
import io_uring.keel_setup_single_issuer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed

/**
 * Production [IoUringRing] backed by the liburing `io_uring_queue_init`
 * / `io_uring_queue_exit` calls and the `keel_setup_*` flag-constant C
 * wrappers in `io_uring.def`. Stateless singleton.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixIoUringRing : IoUringRing {

    override fun setupFlags(coopTaskrun: Boolean, singleIssuer: Boolean, deferTaskrun: Boolean): UInt {
        var flags = 0u
        if (coopTaskrun) flags = flags or keel_setup_coop_taskrun()
        if (singleIssuer) flags = flags or keel_setup_single_issuer()
        if (deferTaskrun) flags = flags or keel_setup_defer_taskrun()
        return flags
    }

    override fun queueInit(entries: Int, ring: CPointer<io_uring>, flags: UInt): Int =
        io_uring_queue_init(entries.toUInt(), ring, flags)

    override fun queueExit(ring: CPointer<io_uring>) {
        io_uring_queue_exit(ring)
    }

    override fun getSqe(ring: CPointer<io_uring>): CPointer<io_uring_sqe>? =
        io_uring_get_sqe(ring)

    override fun submitAndWait(ring: CPointer<io_uring>, minComplete: Int): Int =
        io_uring_submit_and_wait(ring, minComplete.toUInt())

    override fun nextCqe(ring: CPointer<io_uring>, out: Cqe): Boolean {
        val cqe = keel_peek_cqe(ring) ?: return false
        out.userData = io_uring_cqe_get_data64(cqe)
        out.res = cqe.pointed.res
        out.flags = cqe.pointed.flags
        out.hasMore = keel_cqe_has_more(cqe.pointed.flags) != 0
        io_uring_cqe_seen(ring, cqe)
        return true
    }
}
