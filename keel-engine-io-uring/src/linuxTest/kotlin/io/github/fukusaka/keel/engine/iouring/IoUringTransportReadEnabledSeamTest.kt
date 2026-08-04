package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam-level unit tests for [IoUringIoTransport]'s `readEnabled`
 * setter ↔ multishot recv SQE state machine — the Phase C4
 * (backpressure / readEnabled flip) precursor.
 *
 * The audit (#A-audit-5) flagged the
 * `readEnabled` flip ↔ multishot recv rearm sequencing as a high-priority
 * gap with no deterministic seam coverage. Loopback integration tests
 * cannot easily produce the flip-while-already-armed shape — the
 * pipeline rarely backpressures fast enough to cycle readEnabled on a
 * loopback connection — so the latent contract of "one `armRecv()` per
 * dispatch, even on rapid `false → true` flips" was unpinned.
 *
 * Writing the seam test surfaced a latent double-arm bug: the setter
 * called `armRecv()` unconditionally when flipping `false → true`,
 * even when `multishotSlot >= 0`. A second multishot recv SQE was
 * submitted with a fresh slot, while the first multishot recv stayed
 * registered in `callbackSlots[oldSlot]`. The kernel then routed CQEs
 * to both, double-delivering the same `bufId` into the shared
 * wrappers — the same shape as the `-ENOBUFS` double-arm fixed in
 * PR #737 (in the since-removed owned-source read path).
 *
 * The fix gates `armRecv()` on `multishotSlot < 0` — the setter only
 * arms when there is no live multishot recv. `recvStarved` is treated
 * separately: when the previous recv terminated on `-ENOBUFS`, the
 * `rearmRecvAfterStarvation` callback is already registered with the
 * buffer ring, so a subsequent `readEnabled = true` setter call must
 * not race against it. The setter therefore arms only when both
 * `multishotSlot < 0` AND `!recvStarved`.
 *
 * Three seam tests:
 *
 * - `readEnabled false to true arms a multishot recv SQE`: sanity that
 *   the first flip exercises the canonical arm path.
 * - `readEnabled true to false does not cancel the multishot recv SQE`:
 *   pins the current backpressure model (rely on `-ENOBUFS` to stop
 *   the kernel-side read; the SQE itself stays armed). The KDoc at
 *   `armPollAddForFin` claims the recv SQE is dropped on
 *   `readEnabled = false` — that is aspirational; the actual
 *   implementation keeps the SQE armed. Pinning this avoids a
 *   surprise when a future change makes the dispatch match the KDoc.
 * - `readEnabled false to true while multishot already armed does not
 *   double-arm`: the fix invariant.
 *
 * Like the other seam tests in this module, the dispatched setter
 * runs on the test thread without booting the EventLoop pthread.
 * `assertInEventLoop` no-ops pre-`start`.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportReadEnabledSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportReadEnabledSeamTest")

    /**
     * Builds an [IoUringIoTransport] over a fake-backed [IoUringEventLoop]
     * + initialised [ProvidedBufferRing], runs [block], then tears
     * everything down. The transport's fd is synthetic — no kernel
     * syscalls are issued against it because the seam intercepts every
     * relevant path before any real syscall.
     */
    private fun withTransport(
        fake: FakeIoUringRing = FakeIoUringRing(),
        bufRingFake: FakeIoUringBufferRingOps = FakeIoUringBufferRingOps(),
        bufferCount: Int = 4,
        bufferSize: Int = 64,
        fd: Int = 999,
        block: (FakeIoUringRing, IoUringEventLoop, ProvidedBufferRing, IoUringIoTransport) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val bufRing = ProvidedBufferRing(el, logger, bufferCount, bufferSize, bgid = 0, bufRingFake)
        bufRing.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = fd,
            eventLoop = el,
            capabilities = IoUringCapabilities(),
            writeModeSelector = IoModeSelectors.FALLBACK_CQE,
            allocator = DefaultAllocator,
            bufferRing = bufRing,
            fixedFileRegistry = null,
            registeredBufferTable = DisabledRegisteredBufferRegistry,
            preAllocatedIndex = -1,
        )
        try {
            block(fake, el, bufRing, transport)
        } finally {
            bufRing.close()
            el.close()
            fake.dispose()
        }
    }

    @Test
    fun `readEnabled false to true arms a multishot recv SQE`() {
        withTransport { fake, _, _, transport ->
            assertEquals(0, fake.getSqeCalls, "no SQE submitted at construction time")
            transport.readEnabled = true
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the setter prepped IORING_OP_RECV")
            assertEquals(1, fake.getSqeCalls, "exactly one multishot recv SQE was submitted")
        }
    }

    @Test
    fun `readEnabled true to false does not cancel the multishot recv SQE`() {
        // Pins the current backpressure model: the SQE stays armed and the
        // kernel keeps reading into the provided-buffer ring; flow control
        // happens via -ENOBUFS once the ring saturates. A future change
        // that issues an IORING_OP_ASYNC_CANCEL on the setter (matching the
        // armPollAddForFin KDoc's aspirational claim) would surface here.
        withTransport { fake, _, _, transport ->
            transport.readEnabled = true
            val afterArm = fake.getSqeCalls

            transport.readEnabled = false

            assertEquals(
                afterArm,
                fake.getSqeCalls,
                "flipping readEnabled = false must NOT submit any SQE (no cancel today)",
            )
        }
    }

    @Test
    fun `readEnabled false to true while multishot already armed does not double-arm`() {
        // The fix invariant. Without the gate, the setter unconditionally
        // called armRecv() on every false → true flip, so a backpressure
        // cycle (true → false → true) submitted a second multishot recv
        // SQE — the kernel then double-delivered the same bufId CQE to
        // the shared wrappers because both SQEs stayed active.
        withTransport { fake, _, _, transport ->
            transport.readEnabled = true
            val afterFirstArm = fake.getSqeCalls
            assertEquals(IORING_OP_RECV, fake.lastSqeOp())

            transport.readEnabled = false
            transport.readEnabled = true

            assertEquals(
                afterFirstArm,
                fake.getSqeCalls,
                "a re-flip while the multishot recv SQE is still armed must NOT submit a second SQE",
            )
        }
    }

    private companion object {
        // io_uring opcode value from `enum io_uring_op` in <linux/io_uring.h>.
        // Same rationale as the other seam tests: stable kernel ABI.
        private const val IORING_OP_RECV: UByte = 27u
    }
}
