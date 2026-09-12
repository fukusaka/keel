package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ECONNRESET
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for the data CQEs the ring-backed recv tiers
 * (multishot on 6.0+, single-shot buffer-select on 5.19) must not drop:
 *
 * 1. A multishot data CQE without `F_MORE` is the recv's terminal CQE —
 *    the kernel retired the SQE with it, and the loop frees the slot once
 *    the callback returns — so the transport re-arms, as the single-shot
 *    tiers do after every delivery. Otherwise reads stop silently: the
 *    paths that do arm a recv all wait for something that will not come
 *    (an assignment to `readEnabled`, a resume after a pause, a buffer
 *    returned to a starved ring).
 * 2. The transport re-arms only while it may: the gate declines for a
 *    transport the handler closed inside the delivery, for reads the
 *    handler disabled, and for a recv the handler armed through the
 *    setter, and a pause is left to `resumeReads()`.
 * 3. A completion that selected a buffer but delivers nothing returns it —
 *    on both ring tiers, whether the transport is closed (the teardown
 *    raced the CQE) or open with a result that carries no bytes. A
 *    delivered slot goes back through a release, which only a delivery
 *    reaches, so every other outcome returns it here; a slot skipped stays
 *    out of the ring until the loop exits.
 *
 * Like the other seam tests in this module, the dispatched paths run on
 * the test thread without booting the EventLoop pthread; CQEs are scripted
 * through [FakeIoUringRing.enqueueCqe] and drained with `runIteration`.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportRingRecvDataCqeSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportRingRecvDataCqeSeamTest")

    /** Mirror of [IoUringTransportSingleShotRecvSeamTest.withTransport]. */
    private fun withTransport(
        fake: FakeIoUringRing = FakeIoUringRing(),
        bufRingFake: FakeIoUringBufferRingOps = FakeIoUringBufferRingOps(),
        capabilities: IoUringCapabilities = IoUringCapabilities(),
        bufferCount: Int = DEFAULT_BUFFER_COUNT,
        bufferSize: Int = BUFFER_SIZE,
        fd: Int = 999,
        block: (FakeIoUringRing, IoUringEventLoop, ProvidedBufferRing, IoUringIoTransport) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val bufRing = ProvidedBufferRing(el, logger, bufferCount, bufferSize, bgid = 0, bufRingFake)
        bufRing.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = fd,
            eventLoop = el,
            capabilities = capabilities,
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

    /** Encodes a data CQE's flags: `IORING_CQE_F_BUFFER` + buffer ID in the upper bits. */
    private fun bufFlags(bid: Int): UInt = (bid.toUInt() shl 16) or 1u

    /** The `addBuffer` calls [ProvidedBufferRing.returnBuffer] makes for [bids], in order. */
    private fun returned(vararg bids: Int): List<FakeIoUringBufferRingOps.AddCall> =
        bids.map { FakeIoUringBufferRingOps.AddCall(bid = it, offset = 0) }

    /** Enables reads, checks the armed recv has the expected shape, and returns its `user_data`. */
    private fun armRecv(fake: FakeIoUringRing, transport: IoUringIoTransport, multishot: Boolean): ULong {
        transport.readEnabled = true
        assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "readEnabled armed a recv")
        assertEquals(
            if (multishot) IORING_RECV_MULTISHOT else 0u,
            fake.lastSqeIoprio().toUInt() and IORING_RECV_MULTISHOT,
            "the recv is the tier under test",
        )
        return fake.lastSqeUserData()
    }

    @Test
    fun `a multishot data CQE without F_MORE delivers its bytes and re-arms a multishot recv`() {
        withTransport { fake, el, _, transport ->
            var delivered = 0
            transport.onRead = { buf ->
                delivered++
                buf.release()
            }
            val recvUserData = armRecv(fake, transport, multishot = true)
            val sqesBefore = fake.getSqeCalls

            // The kernel ends the multishot with this data: F_MORE clear, so
            // the loop frees the slot after the callback.
            fake.enqueueCqe(userData = recvUserData, res = 64, flags = bufFlags(0), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(1, delivered, "the terminal CQE's bytes reach onRead")
            assertEquals(sqesBefore + 1, fake.getSqeCalls, "the SQE is gone with that CQE, so the recv re-arms")
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the re-arm is a recv")
            assertEquals(
                IORING_RECV_MULTISHOT,
                fake.lastSqeIoprio().toUInt() and IORING_RECV_MULTISHOT,
                "the re-arm is a multishot recv again",
            )
        }
    }

    @Test
    fun `a multishot data CQE without F_MORE while reads are paused leaves the re-arm to resumeReads`() {
        withTransport { fake, el, _, transport ->
            transport.onRead = { buf -> buf.release() }
            val recvUserData = armRecv(fake, transport, multishot = true)
            // The pause cancels the recv; the kernel may still complete it
            // with data before the cancel lands.
            transport.pauseReads()
            val sqesAfterPause = fake.getSqeCalls

            fake.enqueueCqe(userData = recvUserData, res = 64, flags = bufFlags(0), hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(sqesAfterPause, fake.getSqeCalls, "paused: the terminal data CQE must not arm a recv")

            transport.resumeReads()
            assertEquals(sqesAfterPause + 1, fake.getSqeCalls, "resumeReads arms exactly one recv")
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the resume's submission is a recv")
        }
    }

    @Test
    fun `data CQEs after close return their buffers to the ring on the multishot tier`() {
        val ops = FakeIoUringBufferRingOps()
        withTransport(bufRingFake = ops) { fake, el, bufRing, transport ->
            var delivered = 0
            transport.onRead = { delivered++ }
            val recvUserData = armRecv(fake, transport, multishot = true)

            // close() dispatches the teardown; draining it submits the cancel
            // of the in-flight recv. Data CQEs already in the CQ, or completed
            // before the cancel lands, still reach the kept callback.
            transport.close()
            el.runIteration(Cqe())
            val sqesAfterTeardown = fake.getSqeCalls
            val addsBefore = ops.addCalls.size

            fake.enqueueCqe(userData = recvUserData, res = 64, flags = bufFlags(1), hasMore = true)
            fake.enqueueCqe(userData = recvUserData, res = 64, flags = bufFlags(2), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(0, delivered, "no handler sees a buffer after close")
            assertEquals(
                returned(1, 2),
                ops.addCalls.drop(addsBefore),
                "each post-close data CQE's buffer is published back to the ring",
            )
            assertEquals(
                DEFAULT_BUFFER_COUNT - 1,
                bufRing.minAvailableLowWatermark(),
                "each buffer was recorded as taken before it came back, so the occupancy count is level",
            )
            assertEquals(sqesAfterTeardown, fake.getSqeCalls, "a post-close CQE must not re-arm")
        }
    }

    @Test
    fun `a data CQE after close returns its buffer to the ring on the single-shot tier`() {
        val ops = FakeIoUringBufferRingOps()
        withTransport(
            bufRingFake = ops,
            capabilities = IoUringCapabilities(multishotRecv = false),
        ) { fake, el, bufRing, transport ->
            var delivered = 0
            transport.onRead = { delivered++ }
            val recvUserData = armRecv(fake, transport, multishot = false)

            transport.close()
            el.runIteration(Cqe())
            val sqesAfterTeardown = fake.getSqeCalls
            val addsBefore = ops.addCalls.size

            fake.enqueueCqe(userData = recvUserData, res = 64, flags = bufFlags(3), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(0, delivered, "no handler sees a buffer after close")
            assertEquals(
                returned(3),
                ops.addCalls.drop(addsBefore),
                "the post-close data CQE's buffer is published back to the ring",
            )
            assertEquals(
                DEFAULT_BUFFER_COUNT - 1,
                bufRing.minAvailableLowWatermark(),
                "the buffer was recorded as taken before it came back, so the occupancy count is level",
            )
            assertEquals(sqesAfterTeardown, fake.getSqeCalls, "a post-close CQE must not re-arm")
        }
    }

    @Test
    fun `an error completion that carries a buffer returns it and still reports the end`() {
        val ops = FakeIoUringBufferRingOps()
        withTransport(bufRingFake = ops) { fake, el, bufRing, transport ->
            var reports = 0
            transport.onRead = { buf -> buf.release() }
            transport.onReadClosed = { reports++ }
            val recvUserData = armRecv(fake, transport, multishot = true)
            val addsBefore = ops.addCalls.size

            // The transport is open, so the callback runs its result branches.
            // Only a delivery returns a selected buffer, so an error that
            // carries one has to return it here or the slot never comes back.
            fake.enqueueCqe(userData = recvUserData, res = -ECONNRESET, flags = bufFlags(1), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(returned(1), ops.addCalls.drop(addsBefore), "the error's buffer is published back to the ring")
            assertEquals(
                DEFAULT_BUFFER_COUNT - 1,
                bufRing.minAvailableLowWatermark(),
                "the buffer was recorded as taken before it came back",
            )
            // Returning the buffer is half of the branch: the connection
            // ended, and the pipeline has to hear that.
            assertEquals(1, reports, "the error is reported once")
        }
    }

    @Test
    fun `an end-of-file completion that carries a buffer returns it and still reports the end`() {
        val ops = FakeIoUringBufferRingOps()
        withTransport(
            bufRingFake = ops,
            capabilities = IoUringCapabilities(multishotRecv = false),
        ) { fake, el, bufRing, transport ->
            var reports = 0
            transport.onRead = { buf -> buf.release() }
            transport.onReadClosed = { reports++ }
            val recvUserData = armRecv(fake, transport, multishot = false)
            val addsBefore = ops.addCalls.size

            fake.enqueueCqe(userData = recvUserData, res = 0, flags = bufFlags(2), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(returned(2), ops.addCalls.drop(addsBefore), "the EOF's buffer is published back to the ring")
            assertEquals(
                DEFAULT_BUFFER_COUNT - 1,
                bufRing.minAvailableLowWatermark(),
                "the buffer was recorded as taken before it came back",
            )
            assertEquals(1, reports, "the end of file is reported once")
        }
    }

    @Test
    fun `a handler that disables reads on the terminal data CQE stops the re-arm`() {
        withTransport { fake, el, _, transport ->
            transport.onRead = { buf ->
                // Back-pressure applied from inside the delivery: the re-arm
                // that follows it must see the handler's decision.
                transport.readEnabled = false
                buf.release()
            }
            val recvUserData = armRecv(fake, transport, multishot = true)
            val sqesBefore = fake.getSqeCalls

            fake.enqueueCqe(userData = recvUserData, res = 64, flags = bufFlags(0), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(sqesBefore, fake.getSqeCalls, "reads disabled inside the delivery: no recv is armed")
        }
    }

    @Test
    fun `a handler that re-enables reads on the terminal data CQE arms exactly one recv`() {
        withTransport { fake, el, _, transport ->
            transport.onRead = { buf ->
                // The setter arms on the false-to-true edge, so by the time
                // the re-arm runs a recv is already in flight; arming again
                // would orphan the first submission's slot.
                transport.readEnabled = false
                transport.readEnabled = true
                buf.release()
            }
            val recvUserData = armRecv(fake, transport, multishot = true)
            val sqesBefore = fake.getSqeCalls

            fake.enqueueCqe(userData = recvUserData, res = 64, flags = bufFlags(0), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(sqesBefore + 1, fake.getSqeCalls, "exactly one recv is armed, not one per armer")
        }
    }

    @Test
    fun `a handler that closes on the terminal data CQE stops the re-arm`() {
        withTransport { fake, el, _, transport ->
            transport.onReadClosed = { }
            transport.onRead = { buf ->
                buf.release()
                transport.close()
            }
            val recvUserData = armRecv(fake, transport, multishot = true)
            val sqesBefore = fake.getSqeCalls

            fake.enqueueCqe(userData = recvUserData, res = 64, flags = bufFlags(0), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            // A recv armed here would carry a descriptor the teardown has
            // already given up, and on the fixed-file path an index the
            // registry may have handed to the next connection.
            assertEquals(sqesBefore, fake.getSqeCalls, "a transport closed inside the delivery arms nothing")
        }
    }

    private companion object {
        /** Ring size of the harness; small so a test can count its occupancy by hand. */
        private const val DEFAULT_BUFFER_COUNT = 4

        /** Slot size of the harness, and so the largest result a completion may claim. */
        private const val BUFFER_SIZE = 64
    }
}
