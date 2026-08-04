package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ECANCELED
import platform.posix.ECONNRESET
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for the flow-control pause
 * ([IoTransport.pauseReads]) on the io_uring multishot recv tier.
 *
 * A multishot recv SQE ignores `readEnabled` and keeps delivering for as
 * long as the provided buffer ring has buffers — and copy-on-pressure
 * keeps the ring fed — so the pause must cancel the in-flight SQE
 * (`IORING_OP_ASYNC_CANCEL` via the callback-keeping cancel, honouring
 * the in-flight-SQE ownership rules). The races pinned here:
 *
 * 1. the pause submits the cancel;
 * 2. the cancel's `-ECANCELED` terminal is benign — it must NOT fire
 *    `onReadClosed` (a paused connection is not a broken one);
 * 3. resume-before-terminal must not double-arm — the terminal CQE is
 *    the agreed re-arm point;
 * 4. data CQEs already completed when the pause lands still deliver
 *    (the contract's bounded overshoot);
 * 5. a genuine connection error arriving while the cancel is pending
 *    must still fire `onReadClosed` (the benign branch matches only
 *    `-ECANCELED`);
 * 6. the single-shot tier needs no cancel: its in-flight recv delivers
 *    at most once and the re-arm is pause-gated.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportPauseReadsSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportPauseReadsSeamTest")

    /** Fake-backed EventLoop + ring + transport on the requested recv tier. */
    private fun withTransport(
        multishot: Boolean = true,
        block: (FakeIoUringRing, IoUringEventLoop, ProvidedBufferRing, IoUringIoTransport) -> Unit,
    ) {
        val fake = FakeIoUringRing()
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val bufRing = ProvidedBufferRing(
            el,
            logger,
            bufferCount = 8,
            bufferSize = 64,
            bgid = 0,
            FakeIoUringBufferRingOps(),
        )
        bufRing.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = 999,
            eventLoop = el,
            capabilities = IoUringCapabilities(multishotRecv = multishot),
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

    /** Encodes a recv data CQE's flags: `IORING_CQE_F_BUFFER` + buffer ID. */
    private fun bufFlags(bid: Int): UInt = (bid.toUInt() shl 16) or 1u

    @Test
    fun `pause cancels the in-flight multishot recv`() {
        withTransport { fake, el, _, transport ->
            transport.onRead = { it.release() }
            transport.readEnabled = true
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "harness invariant: multishot armed")

            transport.pauseReads()
            assertEquals(
                IORING_OP_ASYNC_CANCEL,
                fake.lastSqeOp(),
                "the pause must cancel the multishot recv — no-re-arm semantics never stop it",
            )

            // Idempotent: a second pause submits nothing further.
            val sqes = fake.getSqeCalls
            transport.pauseReads()
            assertEquals(sqes, fake.getSqeCalls, "a second pause must not submit another cancel")
        }
    }

    @Test
    fun `the cancel's ECANCELED terminal is benign and resume-after re-arms via armRecv`() {
        withTransport { fake, el, _, transport ->
            var readClosed = 0
            transport.onReadClosed = { readClosed++ }
            transport.onRead = { it.release() }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()

            transport.pauseReads()
            fake.enqueueCqe(userData = recvUserData, res = -ECANCELED, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(0, readClosed, "a paused connection is not a broken one — no onReadClosed")

            // Still paused: nothing re-armed.
            val sqesWhilePaused = fake.getSqeCalls
            transport.resumeReads()
            assertEquals(
                sqesWhilePaused + 1,
                fake.getSqeCalls,
                "resume after the terminal re-arms a fresh multishot recv",
            )
            assertEquals(IORING_OP_RECV, fake.lastSqeOp())
        }
    }

    @Test
    fun `resume before the terminal does not double-arm - the terminal CQE re-arms exactly once`() {
        withTransport { fake, el, _, transport ->
            var readClosed = 0
            transport.onReadClosed = { readClosed++ }
            transport.onRead = { it.release() }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()

            transport.pauseReads()
            val afterCancel = fake.getSqeCalls

            // Resume while the cancel's terminal CQE is still in flight:
            // arming now would orphan the old slot (the #741 double-arm
            // shape), so nothing may be submitted yet.
            transport.resumeReads()
            assertEquals(afterCancel, fake.getSqeCalls, "resume before the terminal must not arm")

            // The terminal arrives with the pause already lifted: this is
            // the agreed re-arm point — exactly one fresh recv.
            fake.enqueueCqe(userData = recvUserData, res = -ECANCELED, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(afterCancel + 1, fake.getSqeCalls, "the terminal re-arms exactly once")
            assertEquals(IORING_OP_RECV, fake.lastSqeOp())
            assertEquals(0, readClosed)
        }
    }

    @Test
    fun `data CQEs completed before the cancel still deliver as the bounded overshoot`() {
        withTransport { fake, el, _, transport ->
            var delivered = 0
            transport.onRead = { buf: IoBuf ->
                delivered++
                buf.release()
            }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()

            transport.pauseReads()
            // The kernel had already completed a data CQE when the cancel
            // landed: it must still reach onRead (no data loss), followed
            // by the benign terminal.
            fake.enqueueCqe(userData = recvUserData, res = 5, flags = bufFlags(0), hasMore = true)
            fake.enqueueCqe(userData = recvUserData, res = -ECANCELED, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, delivered, "in-flight data is the allowed overshoot, never dropped")
        }
    }

    @Test
    fun `a genuine error while the cancel is pending still fires onReadClosed`() {
        withTransport { fake, el, _, transport ->
            var readClosed = 0
            transport.onReadClosed = { readClosed++ }
            transport.onRead = { it.release() }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()

            transport.pauseReads()
            // The connection genuinely broke before the cancel resolved:
            // the benign branch matches -ECANCELED only, so the error must
            // surface — masking it would orphan the transport (#746 shape).
            fake.enqueueCqe(userData = recvUserData, res = -ECONNRESET, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, readClosed, "a real error must not be masked by the pause")
        }
    }

    @Test
    fun `the single-shot tier pauses by not re-arming - one in-flight delivery then silence`() {
        withTransport(multishot = false) { fake, el, _, transport ->
            var delivered = 0
            transport.onRead = { buf: IoBuf ->
                delivered++
                buf.release()
            }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()

            // Pause submits NO cancel on this tier (the in-flight
            // single-shot delivers at most once).
            transport.pauseReads()
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "no ASYNC_CANCEL on the single-shot tier")

            // The in-flight recv delivers its one CQE — the bounded
            // overshoot — and must NOT re-arm while paused.
            val beforeCqe = fake.getSqeCalls
            fake.enqueueCqe(userData = recvUserData, res = 4, flags = bufFlags(1), hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, delivered)
            assertEquals(beforeCqe, fake.getSqeCalls, "the single-shot re-arm must be pause-gated")

            transport.resumeReads()
            assertEquals(beforeCqe + 1, fake.getSqeCalls, "resume re-arms the single-shot recv")
            assertEquals(IORING_OP_RECV, fake.lastSqeOp())
        }
    }

    private companion object {
        // io_uring opcode values from `enum io_uring_op` in <linux/io_uring.h>.
        private const val IORING_OP_RECV: UByte = 27u
        private const val IORING_OP_ASYNC_CANCEL: UByte = 14u
    }
}
