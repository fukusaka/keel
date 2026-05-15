package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * In-memory [IoUringProbe] for driving [IoUringCapabilities.detect]'s
 * capability-derivation matrix. Single-threaded.
 *
 * Both probe inputs are plain constructor properties — `detect` calls
 * each exactly once, so no scripting queue is needed. The native `ring`
 * argument to [probeOpcodes] is accepted to satisfy the interface but
 * ignored.
 */
@OptIn(ExperimentalForeignApi::class)
internal class FakeIoUringProbe(
    private val kernelVersion: KernelVersion = KernelVersion(6, 0),
    private val opcodes: OpcodeSupport = OpcodeSupport(sendZc = true, sendmsgZc = true),
) : IoUringProbe {

    override fun kernelVersion(): KernelVersion = kernelVersion

    override fun probeOpcodes(ring: CPointer<io_uring>): OpcodeSupport = opcodes
}
