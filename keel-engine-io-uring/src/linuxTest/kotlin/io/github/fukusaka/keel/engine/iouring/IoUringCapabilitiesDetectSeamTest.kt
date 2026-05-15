package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [IoUringCapabilities.detect]'s
 * capability-derivation matrix via [FakeIoUringProbe] injection. Covers
 * the kernel-version feature gates and the opcode-probe fallback — none
 * of which can be exercised on a single host without the matching
 * kernel.
 *
 * Part of the io_uring native API seam effort. `detect` is a pure
 * function of its probe inputs, so the tests run synchronously on the
 * test thread — no timeout needed.
 *
 * The `ring` argument is a throwaway zero-initialised `io_uring` struct:
 * [FakeIoUringProbe.probeOpcodes] never dereferences it.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringCapabilitiesDetectSeamTest {

    /** Runs [block] with a throwaway `io_uring` ring pointer the fake ignores. */
    private fun withRing(block: (ring: io_uring) -> Unit) = memScoped {
        block(alloc<io_uring>())
    }

    @Test
    fun `detect on a 6_0 kernel with full opcode support enables the modern feature set`() {
        withRing { ring ->
            val caps = IoUringCapabilities.detect(
                ring.ptr,
                FakeIoUringProbe(KernelVersion(6, 0), OpcodeSupport(sendZc = true, sendmsgZc = true)),
            )
            assertTrue(caps.multishotAccept, "5.19+ kernel: multishot accept")
            assertTrue(caps.multishotRecv, "6.0+ kernel: multishot recv")
            assertTrue(caps.providedBufferRing, "5.19+ kernel: provided buffer ring")
            assertTrue(caps.fixedFiles, "5.1+ kernel: fixed files")
            assertTrue(caps.coopTaskrun, "6.0+ kernel: coop taskrun")
            assertTrue(caps.singleIssuer, "6.0+ kernel: single issuer")
            assertTrue(caps.registerRingFd, "5.18+ kernel: register ring fd")
            assertTrue(caps.sendZc, "probe reports SEND_ZC")
            assertTrue(caps.sendmsgZc, "probe reports SENDMSG_ZC")
        }
    }

    @Test
    fun `detect on a 5_1 kernel enables fixed files but not multishot or taskrun`() {
        withRing { ring ->
            val caps = IoUringCapabilities.detect(
                ring.ptr,
                FakeIoUringProbe(KernelVersion(5, 1), OpcodeSupport(sendZc = false, sendmsgZc = false)),
            )
            assertTrue(caps.fixedFiles, "5.1 kernel: fixed files available")
            assertFalse(caps.multishotAccept, "5.1 kernel: multishot accept needs 5.19")
            assertFalse(caps.multishotRecv, "5.1 kernel: multishot recv needs 6.0")
            assertFalse(caps.coopTaskrun, "5.1 kernel: coop taskrun needs 6.0")
            assertFalse(caps.singleIssuer, "5.1 kernel: single issuer needs 6.0")
            assertFalse(caps.registerRingFd, "5.1 kernel: register ring fd needs 5.18")
        }
    }

    @Test
    fun `detect with no opcode support disables sendZc and sendmsgZc`() {
        withRing { ring ->
            val caps = IoUringCapabilities.detect(
                ring.ptr,
                FakeIoUringProbe(KernelVersion(6, 0), OpcodeSupport(sendZc = false, sendmsgZc = false)),
            )
            assertFalse(caps.sendZc, "no probe support: SEND_ZC disabled")
            assertFalse(caps.sendmsgZc, "no probe support: SENDMSG_ZC disabled")
        }
    }

    @Test
    fun `detect with only sendmsgZc support still enables sendZc`() {
        withRing { ring ->
            // sendmsgZc implies sendZc: a 6.1+ kernel that probes SENDMSG_ZC
            // necessarily has SEND_ZC, so the derived sendZc is the OR.
            val caps = IoUringCapabilities.detect(
                ring.ptr,
                FakeIoUringProbe(KernelVersion(6, 1), OpcodeSupport(sendZc = false, sendmsgZc = true)),
            )
            assertTrue(caps.sendZc, "sendmsgZc support implies sendZc")
            assertTrue(caps.sendmsgZc)
        }
    }

    @Test
    fun `detect on a 5_18 kernel enables register ring fd but not coop taskrun`() {
        withRing { ring ->
            // 5.18 is the registerRingFd boundary; coopTaskrun / singleIssuer
            // need 6.0 — guards the per-feature version gate.
            val caps = IoUringCapabilities.detect(
                ring.ptr,
                FakeIoUringProbe(KernelVersion(5, 18), OpcodeSupport(sendZc = false, sendmsgZc = false)),
            )
            assertTrue(caps.registerRingFd, "5.18 kernel: register ring fd boundary")
            assertFalse(caps.coopTaskrun, "5.18 kernel: coop taskrun needs 6.0")
            assertFalse(caps.multishotAccept, "5.18 kernel: multishot accept needs 5.19")
        }
    }

    @Test
    fun `detect on a 5_19 kernel enables multishot accept and provided buffer ring`() {
        withRing { ring ->
            val caps = IoUringCapabilities.detect(
                ring.ptr,
                FakeIoUringProbe(KernelVersion(5, 19), OpcodeSupport(sendZc = false, sendmsgZc = false)),
            )
            assertTrue(caps.multishotAccept, "5.19 kernel: multishot accept boundary")
            assertTrue(caps.providedBufferRing, "5.19 kernel: provided buffer ring boundary")
            assertFalse(caps.multishotRecv, "5.19 kernel: multishot recv needs 6.0")
        }
    }

    @Test
    fun `detect leaves opt-in features off regardless of kernel version`() {
        withRing { ring ->
            val caps = IoUringCapabilities.detect(
                ring.ptr,
                FakeIoUringProbe(KernelVersion(6, 9), OpcodeSupport(sendZc = true, sendmsgZc = true)),
            )
            // These never auto-enable — they are explicit `copy(...)` opt-ins.
            assertFalse(caps.deferTaskrun, "deferTaskrun is opt-in")
            assertFalse(caps.msgRingWakeup, "msgRingWakeup is opt-in")
            assertFalse(caps.acceptDirectAlloc, "acceptDirectAlloc is opt-in")
            assertFalse(caps.napiBusyPoll, "napiBusyPoll is opt-in")
            assertEquals(0, caps.iowqMaxBoundedWorkers, "IO_WQ bounded workers default to kernel default")
            assertEquals(0, caps.iowqMaxUnboundedWorkers, "IO_WQ unbounded workers default to kernel default")
        }
    }
}
