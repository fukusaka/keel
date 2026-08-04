package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ECANCELED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for the fixed-file slot lifetime across teardown
 * with in-flight SQEs.
 *
 * Teardown previously unregistered the transport's fixed-file slot
 * unconditionally — while the just-cancelled recv could still be in
 * flight (`IORING_OP_ASYNC_CANCEL` is asynchronous). The freed slot was
 * then immediately registered by the NEXT accepted connection, and a
 * poll-armed stale request re-resolves its slot index on wakeup, so the
 * dead connection's recv read the next connection's bytes and the kept
 * callback's not-opened branch silently discarded them — observed as a
 * deterministic echo loss on CPU-starved hosts, where the cancel's
 * terminal CQE loses the race to slot reuse.
 *
 * The fix counts in-flight slot-referencing SQEs and defers the
 * unregister to the last terminal CQE; these tests pin the deferral and
 * the immediate-release fast path through [FakeIoUringFileOps]'s
 * `updateSlot` recording (`fd = -1` is the unregister write).
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportFixedSlotLifetimeSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportFixedSlotLifetimeSeamTest")

    /**
     * Builds an [IoUringIoTransport] whose fd is registered in a
     * fake-backed [FixedFileRegistry] (slot 0), using the allocator-recv
     * tier (no buffer ring) so the seam needs no ring scaffolding. The
     * capability matrix matches a pre-ring kernel.
     */
    private fun withFixedSlotTransport(
        fake: FakeIoUringRing = FakeIoUringRing(),
        fileOps: FakeIoUringFileOps = FakeIoUringFileOps(),
        fd: Int = 999,
        block: (FakeIoUringRing, FakeIoUringFileOps, IoUringEventLoop, IoUringIoTransport) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val registry = FixedFileRegistry(el, logger, maxFiles = 8, fileOps = fileOps)
        registry.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = fd,
            eventLoop = el,
            capabilities = IoUringCapabilities(providedBufferRing = false, multishotRecv = false),
            writeModeSelector = IoModeSelectors.FALLBACK_CQE,
            allocator = DefaultAllocator,
            bufferRing = null,
            fixedFileRegistry = registry,
            registeredBufferTable = DisabledRegisteredBufferRegistry,
            preAllocatedIndex = -1,
            readBufferSize = 64,
        )
        try {
            block(fake, fileOps, el, transport)
        } finally {
            el.close()
            fake.dispose()
        }
    }

    /** True when an unregister write (`fd = -1`) for [index] has been recorded. */
    private fun FakeIoUringFileOps.slotUnregistered(index: Int): Boolean =
        slotUpdates.any { it.index == index && it.fd == -1 }

    @Test
    fun `teardown with an in-flight recv defers the slot unregister to the terminal CQE`() {
        withFixedSlotTransport { fake, fileOps, el, transport ->
            transport.onReadClosed = { }
            transport.readEnabled = true // arms the recv on fixed slot 0
            val recvUserData = fake.lastSqeUserData()
            assertEquals(
                FakeIoUringFileOps.SlotUpdate(0, 999),
                fileOps.slotUpdates.single(),
                "harness invariant: the fd was registered into slot 0",
            )

            // Teardown runs with the recv still in flight: the cancel is
            // asynchronous, so the slot must NOT be unregistered yet —
            // a freed slot would be reused by the next connection while
            // the kernel can still resolve the stale recv against it.
            transport.close()
            el.runIteration(Cqe()) // drain the dispatched teardown
            assertFalse(
                fileOps.slotUnregistered(0),
                "the slot must stay registered while the cancelled recv is in flight",
            )

            // The cancelled recv's terminal CQE lands: now (and only now)
            // the deferred unregister runs.
            fake.enqueueCqe(userData = recvUserData, res = -ECANCELED, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertTrue(
                fileOps.slotUnregistered(0),
                "the terminal CQE must perform the deferred unregister",
            )
        }
    }

    @Test
    fun `teardown with no in-flight fixed ops unregisters the slot immediately`() {
        withFixedSlotTransport { _, fileOps, el, transport ->
            transport.onReadClosed = { }
            // No recv armed (readEnabled never set): nothing references
            // the slot, so teardown releases it on the spot.
            transport.close()
            el.runIteration(Cqe())
            assertTrue(
                fileOps.slotUnregistered(0),
                "with no in-flight fixed ops, teardown unregisters immediately",
            )
        }
    }

    @Test
    fun `a completed recv before teardown also allows the immediate unregister`() {
        withFixedSlotTransport { fake, fileOps, el, transport ->
            var readClosed = 0
            transport.onReadClosed = { readClosed++ }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()

            // EOF terminates the recv (terminal CQE) before any teardown:
            // the in-flight count returns to zero through the normal path.
            fake.enqueueCqe(userData = recvUserData, res = 0, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, readClosed)

            // In production the pipeline reacts to onReadClosed by closing
            // the channel; this harness wires a bare counter, so close
            // explicitly. With the recv already terminal, nothing is in
            // flight and teardown unregisters without deferral.
            transport.close()
            el.runIteration(Cqe()) // drain the dispatched teardown
            assertTrue(
                fileOps.slotUnregistered(0),
                "after the recv's terminal CQE, teardown unregisters without deferral",
            )
        }
    }
}
