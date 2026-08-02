package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ENOBUFS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests pinning the `-ENOBUFS` starvation behaviour
 * introduced in PR #641. Loopback integration tests cannot reliably
 * exercise the busy-loop shape — the kernel rarely exhausts the
 * provided-buffer ring at loopback speed — so the regression net
 * lives here.
 *
 * The shape PR #641 fixed: the multishot recv terminates with
 * `-ENOBUFS` (`hasMore = 0`) when the kernel runs out of provided
 * buffers. Re-arming immediately when no buffers are back yet drives
 * the kernel to re-issue `-ENOBUFS` on every submission, burning
 * 100% of the EventLoop on a connection that cannot progress (the
 * recv-buffer-leak / `-ENOBUFS` busy-loop, originally surfaced under
 * `server-http × compression-upload`). The fix has three observable
 * branches:
 *
 * 1. `-ENOBUFS` arrives but the ring already has buffers — typically
 *    happens when a single read delivery exceeds the whole ring (a
 *    ~1 MiB WS frame vs a 512 KiB ring): the kernel fills + reports
 *    every buffer and raises `-ENOBUFS`, but the app returns them
 *    before this CQE is processed. Re-arm now; deferring would stall
 *    forever (no later `returnBuffer` to fire the re-arm).
 * 2. `-ENOBUFS` arrives with the ring genuinely empty — register a
 *    `rearmRecvAfterStarvation` callback on the buffer ring and stop.
 *    `returnBuffer` fires the callback when a buffer comes back.
 *    `recvStarved = true` collapses repeated `-ENOBUFS` (the same
 *    multishot can fire several CQEs in one drain) into one
 *    registration.
 * 3. `returnBuffer` later → the registered callback re-arms.
 *    `recvStarved` is cleared so a subsequent `-ENOBUFS` cycle can
 *    re-register.
 *
 * Three seam tests, one per branch.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportRecvStarvationSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportRecvStarvationSeamTest")

    /**
     * Same harness as [IoUringTransportReadEnabledSeamTest]: fake-backed
     * boss [IoUringEventLoop] + real [ProvidedBufferRing] over a fake
     * `IoUringBufferRingOps`. The transport's fd is synthetic — no
     * kernel syscalls are issued.
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
        }
    }

    /** Drives the multishot recv arm so the `-ENOBUFS` CQE can be scripted. */
    private fun arm(fake: FakeIoUringRing, el: IoUringEventLoop, transport: IoUringIoTransport): ULong {
        transport.readEnabled = true
        assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "test harness invariant: arm happens")
        return fake.lastSqeUserData()
    }

    @Test
    fun `ENOBUFS with buffers already available rearms immediately without deferral`() {
        withTransport { fake, el, bufRing, transport ->
            // Initial `available = bufferCount` from `initOnEventLoop`.
            assertTrue(bufRing.hasAvailable, "fresh ring has buffers available")
            val recvUserData = arm(fake, el, transport)
            val afterArm = fake.getSqeCalls

            // `-ENOBUFS` CQE arrives while the ring still has buffers
            // (the "1 MiB read > ring size" shape — kernel issued -ENOBUFS
            // before observing returnBuffer that already happened).
            fake.enqueueCqe(userData = recvUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            // Branch 1: armRecv fires immediately — a second multishot recv SQE
            // is submitted. The starvation deferral path is NOT taken.
            assertEquals(
                afterArm + 1,
                fake.getSqeCalls,
                "an -ENOBUFS with hasAvailable=true must immediately re-arm",
            )
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the re-arm is another multishot recv")
        }
    }

    @Test
    fun `ENOBUFS with the ring genuinely empty defers via requestRearmOnAvailable`() {
        withTransport { fake, el, bufRing, transport ->
            // Drain all buffers so hasAvailable = false at -ENOBUFS time.
            // This is what the application does as it consumes recv CQEs:
            // each successful res > 0 CQE calls onConsumed() once.
            val bufferCount = 4
            repeat(bufferCount) { bufRing.onConsumed() }
            assertFalse(bufRing.hasAvailable, "drained ring has no buffers available")

            val recvUserData = arm(fake, el, transport)
            val afterArm = fake.getSqeCalls

            fake.enqueueCqe(userData = recvUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            // Branch 2: no immediate rearm — the engine has registered the
            // `rearmRecvAfterStarvation` callback on the ring and is waiting
            // for `returnBuffer` to fire it.
            assertEquals(
                afterArm,
                fake.getSqeCalls,
                "an -ENOBUFS with hasAvailable=false must NOT immediately re-arm (would busy-loop on -ENOBUFS)",
            )
        }
    }

    @Test
    fun `returnBuffer after starvation fires the deferred rearm and another ENOBUFS can re-register`() {
        withTransport { fake, el, bufRing, transport ->
            // Setup starvation: drain ring, arm, deliver -ENOBUFS.
            val bufferCount = 4
            repeat(bufferCount) { bufRing.onConsumed() }
            val firstUserData = arm(fake, el, transport)
            fake.enqueueCqe(userData = firstUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            val afterStarvation = fake.getSqeCalls

            // Branch 3: returnBuffer fires the deferred rearm callback, which
            // re-arms the multishot recv. recvStarved is cleared inside the
            // callback so a subsequent -ENOBUFS cycle can re-register.
            bufRing.returnBuffer(bufId = 0)
            assertEquals(
                afterStarvation + 1,
                fake.getSqeCalls,
                "returnBuffer must fire the deferred rearm and submit a fresh multishot recv",
            )
            assertEquals(IORING_OP_RECV, fake.lastSqeOp())
            val secondUserData = fake.lastSqeUserData()

            // A second -ENOBUFS cycle on the rearmed multishot — drain the
            // ring again so we are back to genuinely empty, then deliver
            // -ENOBUFS and verify a second deferral is accepted (recvStarved
            // was cleared by the rearm callback).
            repeat(bufferCount) { bufRing.onConsumed() }
            fake.enqueueCqe(userData = secondUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            // After the second deferral, no further SQEs were submitted —
            // the engine is waiting on returnBuffer again. A subsequent
            // returnBuffer must fire and re-arm.
            val afterSecondStarvation = fake.getSqeCalls
            assertEquals(
                afterStarvation + 1,
                afterSecondStarvation,
                "the second -ENOBUFS deferral also does not rearm immediately",
            )
            bufRing.returnBuffer(bufId = 1)
            assertEquals(
                afterStarvation + 2,
                fake.getSqeCalls,
                "the second returnBuffer fires the second-cycle rearm too — recvStarved cleared correctly",
            )
        }
    }

    @Test
    fun `large-frame batch with res greater than zero CQEs filling the ring then ENOBUFS rearms immediately at the transport`() {
        // Cross-boundary regression for PR #643: a single read delivery
        // larger than the whole provided-buffer ring (e.g. a ~1 MiB WS frame
        // vs a 512 KiB ring) makes the kernel fill + report every buffer and
        // raise -ENOBUFS all within one CQE batch. The application releases
        // each buffer inside its `onRead` callback (typical pipeline drain),
        // so the ring is already non-empty when the terminal -ENOBUFS CQE is
        // processed and the transport re-arms immediately. PR #643's pre-fix
        // path deferred the re-arm and stalled — `returnBuffer` had already
        // happened during the batch, so no later `returnBuffer` remained to
        // fire the rearmRecvAfterStarvation callback.
        //
        // ProvidedBufferRingSeamTest pins the ring-level invariant
        // (`returns within one CQE batch keep the ring non-empty at -ENOBUFS`);
        // this test pins the transport-level cross-boundary integration of
        // that invariant with the IoUringIoTransport's -ENOBUFS branch.
        val bufferCount = 4
        withTransport(bufferCount = bufferCount) { fake, el, _, transport ->
            // Release each delivered IoBuf inside onRead — what a typical
            // pipeline consumer does (the codec processes the bytes
            // synchronously and the wrapper's reference count drops to zero
            // at the end of onRead, triggering RingBufferIoBuf.release →
            // ProvidedBufferRing.returnBuffer).
            transport.onRead = { buf -> buf.release() }

            val recvUserData = arm(fake, el, transport)
            val afterArm = fake.getSqeCalls

            // Enqueue bufferCount res > 0 CQEs (one per buffer slot), all
            // with hasMore = true, followed by the terminal -ENOBUFS CQE.
            // The single runIteration drain processes them in order; each
            // res > 0 fires onRead → release → returnBuffer.
            for (bid in 0 until bufferCount) {
                fake.enqueueCqe(
                    userData = recvUserData,
                    res = 64,
                    flags = (bid.toUInt() shl 16) or 1u, // F_BUFFER + bid encoded in upper bits
                    hasMore = true,
                )
            }
            fake.enqueueCqe(userData = recvUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            // The transport must re-arm immediately at the terminal -ENOBUFS
            // because the in-batch returnBuffer calls left the ring non-empty.
            // Without PR #643's fix this would have deferred (hasAvailable was
            // computed once per CQE without the onConsumed/returnBuffer cross
            // tracking) and the rest of the large frame would never arrive.
            assertEquals(
                afterArm + 1,
                fake.getSqeCalls,
                "the transport must immediately re-arm when in-batch returnBuffer calls keep the ring non-empty",
            )
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "the re-arm is another multishot recv")
        }
    }

    @Test
    fun `every ENOBUFS CQE is recorded on the ring's occupancy counters regardless of branch`() {
        withTransport { fake, el, bufRing, transport ->
            // Branch 1 (immediate re-arm): ring still has buffers.
            val recvUserData = arm(fake, el, transport)
            fake.enqueueCqe(userData = recvUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1L, bufRing.recvEnobufsCount(), "the immediate-re-arm branch must count the CQE")
            assertEquals(0L, bufRing.deferredRearmCount(), "no deferral happened yet")

            // Branch 2 (deferred re-arm): drain the ring, then starve again.
            val bufferCount = 4
            repeat(bufferCount) { bufRing.onConsumed() }
            val secondUserData = fake.lastSqeUserData()
            fake.enqueueCqe(userData = secondUserData, res = -ENOBUFS, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(2L, bufRing.recvEnobufsCount(), "the deferred branch must count the CQE too")
            assertEquals(1L, bufRing.deferredRearmCount(), "the deferred branch registers one re-arm")
        }
    }

    private companion object {
        // io_uring opcode value from `enum io_uring_op` in <linux/io_uring.h>.
        private const val IORING_OP_RECV: UByte = 27u
    }
}
