package io.github.fukusaka.keel.engine.iouring

import io_uring.IORING_OP_SENDMSG_ZC
import io_uring.IORING_OP_SEND_ZC
import io_uring.io_uring
import io_uring.keel_get_probe_ring
import io_uring.keel_opcode_supported
import io_uring.keel_probe_free
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Production [IoUringProbe] backed by `uname(2)` (via [KernelVersion.current])
 * and the liburing opcode probe. Stateless singleton.
 *
 * [probeOpcodes] owns the full `get_probe_ring` → `opcode_supported` →
 * `probe_free` lifecycle so a single probe answers every opcode query.
 * A `null` probe ring (old / restricted kernel) yields all-unsupported.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixIoUringProbe : IoUringProbe {

    override fun kernelVersion(): KernelVersion = KernelVersion.current()

    override fun probeOpcodes(ring: CPointer<io_uring>): OpcodeSupport {
        val probe = keel_get_probe_ring(ring)
        val support = OpcodeSupport(
            sendZc = probe != null && keel_opcode_supported(probe, IORING_OP_SEND_ZC) != 0,
            sendmsgZc = probe != null && keel_opcode_supported(probe, IORING_OP_SENDMSG_ZC) != 0,
        )
        if (probe != null) keel_probe_free(probe)
        return support
    }
}
