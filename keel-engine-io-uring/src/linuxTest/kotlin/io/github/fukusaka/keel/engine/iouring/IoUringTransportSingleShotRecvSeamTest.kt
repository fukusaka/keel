package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ENOBUFS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for the single-shot buffer-select recv fallback —
 * the read mode [IoUringIoTransport] selects when the kernel has a
 * provided buffer ring (5.19+) but no `IORING_RECV_MULTISHOT` (6.0+),
 * i.e. `IoUringCapabilities(multishotRecv = false)`.
 *
 * The fallback shares the ring, the `wrappers[bufId]` delivery, and the
 * `-ENOBUFS` deferred re-arm with the multishot mode, but its SQE
 * lifecycle differs: every CQE terminates the SQE, so delivery re-arms
 * explicitly and backpressure is inherent (`readEnabled = false` simply
 * stops the re-arm — the epoll/kqueue semantics — instead of relying on
 * ring exhaustion).
 *
 * Both modes prep `IORING_OP_RECV`; the multishot-ness lives in the
 * SQE's `ioprio` field (`IORING_RECV_MULTISHOT`, bit 1), so these tests
 * pair [FakeIoUringRing.lastSqeOp] with [FakeIoUringRing.lastSqeIoprio]
 * to pin which shape was armed.
 *
 * Like the other seam tests in this module, the dispatched paths run on
 * the test thread without booting the EventLoop pthread
 * (`assertInEventLoop` no-ops pre-`start`); CQEs are scripted through
 * [FakeIoUringRing.enqueueCqe] and drained with `runIteration`.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportSingleShotRecvSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportSingleShotRecvSeamTest")

    /**
     * Same harness as [IoUringTransportReadEnabledSeamTest], with the
     * capability matrix parameterised: `multishotRecv = false` selects
     * the single-shot fallback under test; one sanity test passes the
     * default capabilities to pin the multishot shape for contrast.
     */
    private fun withTransport(
        fake: FakeIoUringRing = FakeIoUringRing(),
        bufRingFake: FakeIoUringBufferRingOps = FakeIoUringBufferRingOps(),
        capabilities: IoUringCapabilities = IoUringCapabilities(multishotRecv = false),
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

    @Test
    fun `readEnabled true with multishotRecv false arms a single-shot buffer-select recv`() {
        withTransport { fake, _, _, transport ->
            assertEquals(0, fake.getSqeCalls, "no SQE submitted at construction time")
            transport.readEnabled = true
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the setter prepped IORING_OP_RECV")
            assertEquals(
                0u,
                fake.lastSqeIoprio().toUInt() and IORING_RECV_MULTISHOT,
                "the single-shot prep must NOT set IORING_RECV_MULTISHOT in ioprio",
            )
            assertEquals(1, fake.getSqeCalls, "exactly one recv SQE was submitted")
        }
    }

    @Test
    fun `readEnabled true with default capabilities arms a multishot recv for contrast`() {
        withTransport(capabilities = IoUringCapabilities()) { fake, _, _, transport ->
            transport.readEnabled = true
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the setter prepped IORING_OP_RECV")
            assertEquals(
                IORING_RECV_MULTISHOT,
                fake.lastSqeIoprio().toUInt() and IORING_RECV_MULTISHOT,
                "the multishot prep sets IORING_RECV_MULTISHOT in ioprio",
            )
        }
    }

    @Test
    fun `data CQE delivers bytes to onRead and re-arms a fresh single-shot recv`() {
        withTransport { fake, el, _, transport ->
            var delivered = -1
            transport.onRead = { buf: IoBuf ->
                delivered = buf.readableBytes
                buf.release()
            }
            transport.readEnabled = true
            val afterArm = fake.getSqeCalls
            val recvUserData = fake.lastSqeUserData()

            fake.enqueueCqe(userData = recvUserData, res = 13, flags = bufFlags(bid = 2), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(13, delivered, "onRead received the CQE's byte count")
            assertEquals(
                afterArm + 1,
                fake.getSqeCalls,
                "a single-shot data CQE must re-arm exactly one fresh recv SQE",
            )
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the re-arm is another recv")
            assertEquals(
                0u,
                fake.lastSqeIoprio().toUInt() and IORING_RECV_MULTISHOT,
                "the re-arm stays single-shot",
            )
        }
    }

    @Test
    fun `readEnabled false inside onRead stops the re-arm until the next true flip`() {
        withTransport { fake, el, _, transport ->
            transport.onRead = { buf: IoBuf ->
                // Backpressure: the handler pauses reads mid-delivery.
                transport.readEnabled = false
                buf.release()
            }
            transport.readEnabled = true
            val afterArm = fake.getSqeCalls
            val recvUserData = fake.lastSqeUserData()

            fake.enqueueCqe(userData = recvUserData, res = 13, flags = bufFlags(bid = 0), hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            // Single-shot backpressure is inherent: no live SQE remains and
            // the delivery did not re-arm.
            assertEquals(
                afterArm,
                fake.getSqeCalls,
                "with readEnabled=false at delivery end, the single-shot path must not re-arm",
            )

            // Resuming reads arms again through the setter's recvSlot<0 gate.
            transport.readEnabled = true
            assertEquals(
                afterArm + 1,
                fake.getSqeCalls,
                "the next readEnabled=true flip re-arms exactly once",
            )
        }
    }

    @Test
    fun `ENOBUFS with the ring genuinely empty defers and returnBuffer re-arms single-shot`() {
        withTransport { fake, el, bufRing, transport ->
            // Drain the ring's availability accounting so the -ENOBUFS CQE
            // takes the deferral branch (hasAvailable = false).
            repeat(4) { bufRing.onConsumed() }
            assertFalse(bufRing.hasAvailable, "drained ring has no buffers available")

            transport.readEnabled = true
            val afterArm = fake.getSqeCalls
            val recvUserData = fake.lastSqeUserData()

            fake.enqueueCqe(userData = recvUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(afterArm, fake.getSqeCalls, "deferred: no immediate re-arm on starvation")

            // A buffer coming back fires the registered rearm callback.
            bufRing.returnBuffer(0)
            assertEquals(
                afterArm + 1,
                fake.getSqeCalls,
                "returnBuffer must fire the deferred re-arm exactly once",
            )
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the deferred re-arm is a recv")
            assertEquals(
                0u,
                fake.lastSqeIoprio().toUInt() and IORING_RECV_MULTISHOT,
                "the deferred re-arm stays single-shot",
            )
        }
    }

    @Test
    fun `EOF CQE fires onReadClosed once and does not re-arm`() {
        withTransport { fake, el, _, transport ->
            var readClosed = 0
            transport.onReadClosed = { readClosed++ }
            transport.readEnabled = true
            val afterArm = fake.getSqeCalls
            val recvUserData = fake.lastSqeUserData()

            // res = 0: orderly peer shutdown (EOF) — same branch as any
            // non-ENOBUFS error.
            fake.enqueueCqe(userData = recvUserData, res = 0, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(1, readClosed, "EOF fires onReadClosed exactly once")
            assertEquals(afterArm, fake.getSqeCalls, "EOF must not re-arm the recv")
        }
    }

    @Test
    fun `readEnabled flip false to true while single-shot armed does not double-arm`() {
        withTransport { fake, _, _, transport ->
            transport.readEnabled = true
            assertEquals(1, fake.getSqeCalls, "first flip arms one recv SQE")

            // Rapid false → true with the SQE still in flight: the setter's
            // recvSlot < 0 gate must hold for the single-shot mode exactly
            // as it does for multishot (PR #741's invariant).
            transport.readEnabled = false
            transport.readEnabled = true
            assertEquals(
                1,
                fake.getSqeCalls,
                "no second recv SQE while one is in flight",
            )
        }
    }

    companion object {
        /** `IORING_OP_RECV` kernel ABI opcode (io_uring.h). */
        private const val IORING_OP_RECV: UByte = 27u

        /**
         * `IORING_RECV_MULTISHOT` ioprio flag bit (io_uring.h: `1U << 1`).
         * Precomputed because cinterop does not surface the `#define`.
         */
        private const val IORING_RECV_MULTISHOT: UInt = 2u
    }
}
