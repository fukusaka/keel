package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ECANCELED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level unit tests pinning the peer-FIN detection path
 * introduced in PR #475: a single-shot `IORING_OP_POLL_ADD` watching
 * for `POLLRDHUP | POLLHUP | POLLERR` (no `POLLIN`) that detects peer
 * close while `readEnabled = false`, complementing the multishot
 * recv path's `res = 0` EOF signal which only fires while a recv SQE
 * is queued.
 *
 * The original regression coverage lives in
 * [IoUringPeerCloseWithDisabledReadTest] — a real-socket integration
 * test that exercises the full FIN delivery flow through the kernel.
 * The seam pin here covers four invariants without a kernel:
 *
 * 1. `onChannelAttached` arms exactly one `POLL_ADD` SQE on the
 *    EventLoop thread, with the canonical
 *    `POLLRDHUP | POLLHUP | POLLERR` mask (no `POLLIN`) — bytes in
 *    the receive buffer must NOT fire this CQE.
 * 2. A `POLL_ADD` CQE with `res >= 0` (the kernel's standard
 *    peer-close completion shape: `res` carries the matched revents)
 *    fires `onReadClosed` exactly once.
 * 3. A `POLL_ADD` CQE with `res < 0` (e.g. `-ECANCELED` when the
 *    teardown path cancelled the SQE before close) does NOT fire
 *    `onReadClosed`.
 * 4. The `fireReadClosedOnce` guard ensures that two concurrent
 *    peer-close signals — one through `POLL_ADD`, one through a
 *    multishot recv `res = 0` CQE — together fire `onReadClosed`
 *    just once.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportPeerFinSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportPeerFinSeamTest")

    /** Mirror of [IoUringTransportRecvStarvationSeamTest.withTransport]. */
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
    fun `onChannelAttached arms POLL_ADD with POLLRDHUP HUP ERR mask and no POLLIN`() {
        withTransport { fake, el, _, transport ->
            transport.onChannelAttached()
            // The dispatch in armPollAddForFin posts a task onto the
            // EventLoop queue; one runIteration drains it and submits the
            // POLL_ADD SQE.
            assertTrue(el.runIteration(Cqe()))

            assertEquals(IORING_OP_POLL_ADD, fake.lastSqeOp(), "POLL_ADD opcode written to the SQE")
            val mask = fake.lastPollSqeMask()
            assertEquals(EXPECTED_MASK, mask, "mask must be POLLRDHUP | POLLHUP | POLLERR — no POLLIN")
            assertTrue(
                (mask and POLLIN_BIT) == 0u,
                "POLLIN must be excluded so application bytes do not fire this CQE",
            )
        }
    }

    @Test
    fun `POLL_ADD CQE with non-negative res fires onReadClosed exactly once`() {
        withTransport { fake, el, _, transport ->
            var onReadClosedFires = 0
            transport.onReadClosed = { onReadClosedFires++ }

            transport.onChannelAttached()
            assertTrue(el.runIteration(Cqe()))
            val pollUserData = fake.lastSqeUserData()

            // The kernel delivers a POLL_ADD completion with res carrying
            // the matched revents (POLLRDHUP here). scriptPollCqe is the
            // A2 step (d) alias documenting the intent.
            fake.scriptPollCqe(userData = pollUserData, revents = POLLRDHUP_BIT)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(1, onReadClosedFires, "POLL_ADD with res >= 0 fires onReadClosed once")
        }
    }

    @Test
    fun `POLL_ADD CQE with ECANCELED does not fire onReadClosed`() {
        withTransport { fake, el, _, transport ->
            var onReadClosedFires = 0
            transport.onReadClosed = { onReadClosedFires++ }

            transport.onChannelAttached()
            assertTrue(el.runIteration(Cqe()))
            val pollUserData = fake.lastSqeUserData()

            // teardownOnEventLoop cancels the in-flight POLL_ADD before
            // close(fd); the kernel delivers a -ECANCELED CQE that must be
            // a no-op — the close path handles cleanup.
            fake.enqueueCqe(userData = pollUserData, res = -ECANCELED, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(0, onReadClosedFires, "-ECANCELED on POLL_ADD must NOT fire onReadClosed")
        }
    }

    @Test
    fun `POLL_ADD FIN and multishot recv res zero together fire onReadClosed only once`() {
        // The fireReadClosedOnce guard exists for this exact race: both the
        // POLL_ADD CQE and a multishot recv res = 0 CQE may observe the
        // same peer FIN. The guard makes sure onReadClosed fires once even
        // when both signals arrive.
        withTransport { fake, el, _, transport ->
            var onReadClosedFires = 0
            transport.onReadClosed = { onReadClosedFires++ }

            // Arm both POLL_ADD (onChannelAttached) and multishot recv
            // (readEnabled = true). Drain each task / CQE so both SQEs land
            // and we can identify their user_data.
            transport.onChannelAttached()
            assertTrue(el.runIteration(Cqe()))
            val pollUserData = fake.lastSqeUserData()

            transport.readEnabled = true
            // readEnabled.setter calls armRecv() synchronously (no dispatch)
            // so the multishot recv SQE is already submitted; just capture it.
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "armRecv submitted multishot recv")
            val recvUserData = fake.lastSqeUserData()

            // Deliver FIN on both paths: a multishot recv res = 0 CQE
            // (peer closed connection) AND the POLL_ADD CQE firing on the
            // same FIN event.
            fake.enqueueCqe(userData = recvUserData, res = 0, flags = 0u, hasMore = false)
            fake.scriptPollCqe(userData = pollUserData, revents = POLLRDHUP_BIT)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(
                1,
                onReadClosedFires,
                "concurrent FIN signals through POLL_ADD and multishot recv must fire onReadClosed exactly once",
            )
        }
    }

    @Test
    fun `onChannelAttached called twice does not double-arm POLL_ADD`() {
        // Deep-audit follow-up (F-2): defensive guard against double-arming the
        // peer-FIN POLL_ADD. The previous body had no `if (pollAddFinSlot >= 0)
        // gate, so a second `onChannelAttached` call would overwrite
        // `pollAddFinSlot` and orphan the first SQE's callback slot in
        // `callbackSlots[]` until the kernel delivers its CQE — the same
        // double-arm shape as the gates added in PR #737 (the since-removed
        // owned-source read path) and PR #741
        // (IoUringIoTransport.readEnabled). The fix wraps the
        // arm body in a `pollAddFinSlot >= 0` short-circuit; pin it by
        // calling onChannelAttached twice in a row and asserting exactly one
        // POLL_ADD SQE was submitted.
        withTransport { fake, el, _, transport ->
            // First attach: the dispatched arm-block lands on the EL task
            // queue; runIteration drains it and submits the POLL_ADD SQE.
            transport.onChannelAttached()
            assertTrue(el.runIteration(Cqe()))
            assertEquals(IORING_OP_POLL_ADD, fake.lastSqeOp())
            val afterFirstAttach = fake.getSqeCalls

            // Second attach: dispatches another arm-block. After drain the
            // guard short-circuits, so no second POLL_ADD SQE is submitted.
            transport.onChannelAttached()
            assertTrue(el.runIteration(Cqe()))

            assertEquals(
                afterFirstAttach,
                fake.getSqeCalls,
                "second onChannelAttached must NOT submit a second POLL_ADD SQE",
            )
        }
    }

    private companion object {
        // io_uring opcode values from `enum io_uring_op` in <linux/io_uring.h>.
        private const val IORING_OP_POLL_ADD: UByte = 6u
        private const val IORING_OP_RECV: UByte = 27u

        // Linux <asm-generic/poll.h> bit values matching the keel cinterop
        // wrappers (`KEEL_POLLRDHUP` / `KEEL_POLLHUP` / `KEEL_POLLERR`).
        private const val POLLIN_BIT: UInt = 0x0001u
        private const val POLLHUP_BIT: UInt = 0x0010u
        private const val POLLERR_BIT: UInt = 0x0008u
        private const val POLLRDHUP_BIT: UInt = 0x2000u

        // A plain `val`, not `const`: a const initializer may not call `or`, and
        // spelling the mask out of its named bits is worth more than constness here —
        // the previous literal 0x2018u was correct only as long as the comment
        // beside it stayed in step with the bits above.
        private val EXPECTED_MASK: UInt = POLLRDHUP_BIT or POLLHUP_BIT or POLLERR_BIT
    }
}
