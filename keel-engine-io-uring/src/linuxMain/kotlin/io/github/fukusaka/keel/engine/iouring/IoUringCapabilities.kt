package io.github.fukusaka.keel.engine.iouring

import io_uring.IORING_OP_SEND_ZC
import io_uring.IORING_OP_SENDMSG_ZC
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
 *
 * **Detection strategy**:
 * - Opcode probe (liburing `io_uring_get_probe_ring`): for opcodes like SEND_ZC
 * - Kernel version (`uname`): for features that are opcode flags (multishot)
 *   or APIs (provided buffer ring) not detectable via opcode probe
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
    /** Fixed file descriptors (Linux 5.1+). Per-SQE fd lookup elimination. */
    val fixedFiles: Boolean = true,
    /**
     * Registered buffers for SEND_ZC_FIXED (Linux 5.1+).
     *
     * Pre-pins pooled buffer pages via `io_uring_register_buffers`,
     * eliminating per-send page pinning in SEND_ZC. Only effective
     * when [sendZc] is also enabled.
     *
     * **Default: false** (opt-in). Benefit is primarily on real NICs
     * with DMA; loopback shows +7.9% on /large (100KB).
     *
     * **Memory overhead** when enabled:
     * - Userspace: pool warmup fills all slots (8 KiB × 8 = 64 KiB / EventLoop).
     *   Buffers are pooled, not additional memory.
     * - Kernel: page table entries for pinned pages (~16 pages × 4 KiB
     *   = 64 KiB pinned / EventLoop for default 8 KiB × 8 slot pool).
     * - HashMap: address→index mapping (~512 bytes / EventLoop).
     * - Total for 4 EventLoops: ~256 KiB kernel pinned + ~2 KiB HashMap.
     */
    val registeredBuffers: Boolean = false,
    /** Zero-copy send (Linux 6.0+). Two CQEs per operation. */
    val sendZc: Boolean = true,
    /**
     * Zero-copy sendmsg (Linux 6.1+). Gather write + zero-copy. Two CQEs per operation.
     *
     * Implies [sendZc]: if sendmsgZc is true, sendZc must also be true because
     * [IoMode.SENDMSG_ZC] falls back to SEND_ZC for single-buffer flushes.
     * [detect] enforces this invariant. Manual [copy] callers must not set
     * `sendmsgZc = true, sendZc = false`.
     */
    val sendmsgZc: Boolean = true,
) {
    companion object {
        /**
         * Auto-detect capabilities from the running kernel.
         *
         * Uses opcode probe for features detectable at opcode level (SEND_ZC),
         * and kernel version for features that are opcode flags or APIs
         * (multishot, provided buffer ring).
         *
         * @param ring An initialised io_uring ring (used for opcode probing).
         */
        fun detect(ring: CPointer<io_uring>): IoUringCapabilities {
            val kv = KernelVersion.current()
            val probe = keel_get_probe_ring(ring)

            val sendZcSupported = probe != null && keel_opcode_supported(probe, IORING_OP_SEND_ZC) != 0
            val sendmsgZcSupported = probe != null && keel_opcode_supported(probe, IORING_OP_SENDMSG_ZC) != 0
            val caps = IoUringCapabilities(
                multishotAccept = kv >= KernelVersion(5, 19),
                multishotRecv = kv >= KernelVersion(6, 0),
                providedBufferRing = kv >= KernelVersion(5, 19),
                fixedFiles = kv >= KernelVersion(5, 1),
                // sendmsgZc implies sendZc (6.1+ kernel has both opcodes).
                sendZc = sendZcSupported || sendmsgZcSupported,
                sendmsgZc = sendmsgZcSupported,
            )

            if (probe != null) keel_probe_free(probe)
            return caps
        }

        /** Minimal capabilities: all advanced features disabled. */
        val MINIMAL = IoUringCapabilities(
            multishotAccept = false,
            multishotRecv = false,
            providedBufferRing = false,
            fixedFiles = false,
            sendZc = false,
            sendmsgZc = false,
        )
    }
}
