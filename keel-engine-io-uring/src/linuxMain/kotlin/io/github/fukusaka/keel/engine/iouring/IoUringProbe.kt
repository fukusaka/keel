package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * io_uring opcode support reported by an `io_uring_get_probe_ring` probe.
 *
 * Only the opcodes [IoUringCapabilities.detect] actually consults are
 * carried — extend as new opcode-gated features are added.
 */
internal data class OpcodeSupport(
    /** `IORING_OP_SEND_ZC` — zero-copy send (Linux 6.0+). */
    val sendZc: Boolean,
    /** `IORING_OP_SENDMSG_ZC` — zero-copy gather send (Linux 6.1+). */
    val sendmsgZc: Boolean,
)

/**
 * Semantic abstraction over the kernel-capability probes that
 * [IoUringCapabilities.detect] depends on — the running kernel version
 * (`uname(2)`) and the io_uring opcode probe
 * (`io_uring_get_probe_ring` / `io_uring_opcode_supported`).
 *
 * Introduced so the capability-derivation matrix in [IoUringCapabilities.detect]
 * — which kernel version enables which feature, and the opcode-probe
 * fallback when a probe is unavailable — is reachable from seam tests
 * without a real Linux kernel.
 *
 * Part of the io_uring native API seam effort (sibling of
 * `IoUringSyscallOps` / `IoUringRing` and the register-class `*Ops`
 * seams).
 *
 * Thread safety: the production implementation is stateless and safe to
 * share; a fake is single-threaded (driven by the test thread).
 */
@OptIn(ExperimentalForeignApi::class)
internal interface IoUringProbe {

    /** Returns the running kernel's `major.minor` version. */
    fun kernelVersion(): KernelVersion

    /**
     * Probes [ring] for opcode support via `io_uring_get_probe_ring`.
     * When the probe itself is unavailable (old kernel, restricted
     * environment) every opcode is reported unsupported.
     */
    fun probeOpcodes(ring: CPointer<io_uring>): OpcodeSupport
}
