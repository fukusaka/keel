package io.github.fukusaka.keel.engine.iouring

import io_uring.IORING_OP_SEND_ZC
import io_uring.io_uring
import io_uring.keel_get_probe_ring
import io_uring.keel_opcode_supported
import io_uring.keel_probe_free
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Runtime-detected io_uring kernel capabilities.
 *
 * Controls which io_uring features are used by the engine. Features
 * that are not available on the running kernel are automatically
 * disabled, and the engine falls back to simpler alternatives:
 *
 * | Feature | Fallback |
 * |---------|----------|
 * | multishot accept | single-shot accept (one SQE per accept) |
 * | multishot recv + provided buffer ring | pull-model read via Channel.read |
 * | SEND_ZC | regular IORING_OP_SEND (IoMode.CQE) |
 * | SINGLE_ISSUER / COOP_TASKRUN | ring init without flags |
 *
 * **Detection strategy**:
 * - Opcode probe (liburing `io_uring_get_probe_ring`): for opcodes like SEND_ZC
 * - Kernel version (`uname`): for features that are opcode flags (multishot)
 *   or APIs (provided buffer ring) not detectable via opcode probe
 * - Ring init retry: for setup flags (SINGLE_ISSUER, COOP_TASKRUN)
 *
 * **User override**: pass a custom [IoUringCapabilities] to [IoUringEngine]
 * to force-enable or force-disable features (e.g., for testing or debugging).
 *
 * ```
 * // Auto-detect (default)
 * IoUringEngine()
 *
 * // Disable SEND_ZC
 * IoUringEngine(capabilities = IoUringCapabilities.detect(ring).copy(sendZc = false))
 *
 * // Force all features off (minimal mode)
 * IoUringEngine(capabilities = IoUringCapabilities.MINIMAL)
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
data class IoUringCapabilities(
    /** Multishot accept (Linux 5.19+). One SQE → multiple accept CQEs. */
    val multishotAccept: Boolean = true,
    /** Multishot recv (Linux 6.0+). One SQE → multiple recv CQEs with provided buffers. */
    val multishotRecv: Boolean = true,
    /** Provided buffer ring (Linux 5.19+). Kernel-managed buffer selection. */
    val providedBufferRing: Boolean = true,
    /** Zero-copy send (Linux 6.0+). Two CQEs per operation. */
    val sendZc: Boolean = true,
    /**
     * IORING_SETUP_SINGLE_ISSUER (Linux 6.0+). Kernel lock reduction.
     *
     * **Currently disabled by default** in [detect] due to incompatibility
     * with the EventLoop threading model. SINGLE_ISSUER requires all SQE
     * submissions to originate from a single thread, but the current
     * EventLoop receives `dispatch()` calls from external threads (e.g.,
     * test coroutines, Ktor pipeline on Dispatchers.Default). Enabling
     * this causes SIGTERM-like failures in `io_uring_enter()` from
     * external threads.
     *
     * Force-enabling via `IoUringCapabilities(singleIssuer = true)` will
     * apply the flag to `io_uring_queue_init`, but may cause test hangs
     * or timeouts. Requires EventLoop redesign to guarantee single-thread
     * SQE submission before enabling.
     */
    val singleIssuer: Boolean = true,
    /**
     * IORING_SETUP_COOP_TASKRUN (Linux 5.19+). CQE scheduling optimisation.
     *
     * **Currently disabled by default** in [detect] due to incompatibility
     * with the EventLoop's `io_uring_submit_and_wait(1)` blocking loop.
     * COOP_TASKRUN defers CQE generation to the next `io_uring_enter()`
     * call instead of delivering via kernel task-work interrupts. This
     * creates a deadlock: the EventLoop blocks in `submit_and_wait`
     * waiting for a CQE, but the CQE won't be generated until the next
     * `io_uring_enter()`.
     *
     * Resolving this requires switching to `io_uring_submit_and_wait_timeout`
     * or polling with `IORING_SETUP_TASKRUN_FLAG` to detect pending CQEs.
     *
     * Force-enabling via `IoUringCapabilities(coopTaskrun = true)` will
     * apply the flag to `io_uring_queue_init`, but will cause deadlocks.
     */
    val coopTaskrun: Boolean = true,
) {
    companion object {
        /**
         * Auto-detect capabilities from the running kernel.
         *
         * Uses opcode probe for features detectable at opcode level (SEND_ZC),
         * and kernel version for features that are opcode flags or APIs
         * (multishot, provided buffer ring, setup flags).
         *
         * @param ring An initialised io_uring ring (used for opcode probing).
         */
        fun detect(ring: CPointer<io_uring>): IoUringCapabilities {
            val kv = KernelVersion.current()
            val probe = keel_get_probe_ring(ring)

            val caps = IoUringCapabilities(
                multishotAccept = kv >= KernelVersion(5, 19),
                multishotRecv = kv >= KernelVersion(6, 0),
                providedBufferRing = kv >= KernelVersion(5, 19),
                sendZc = probe != null && keel_opcode_supported(probe, IORING_OP_SEND_ZC) != 0,
                singleIssuer = kv >= KernelVersion(6, 0),
                coopTaskrun = kv >= KernelVersion(5, 19),
            )

            if (probe != null) keel_probe_free(probe)
            return caps
        }

        /** Minimal capabilities: all advanced features disabled. */
        val MINIMAL = IoUringCapabilities(
            multishotAccept = false,
            multishotRecv = false,
            providedBufferRing = false,
            sendZc = false,
            singleIssuer = false,
            coopTaskrun = false,
        )
    }
}
