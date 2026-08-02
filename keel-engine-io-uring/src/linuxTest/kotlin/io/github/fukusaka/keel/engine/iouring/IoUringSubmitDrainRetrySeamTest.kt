package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Seam-level tests for [IoUringEventLoop.acquireSqe]'s SQ-ring-full drain
 * behaviour.
 *
 * When `io_uring_get_sqe` returns `null` (SQ ring full), `acquireSqe`
 * submits the queued SQEs (`io_uring_submit`) to free their ring entries and
 * retries `getSqe` **exactly once**. This replaces the previous
 * `getSqe() ?: error(...)` fail-fast that, once a caller had already acquired
 * state (an ownership snapshot, an in-flight-op counter increment), stranded
 * that state on the throw while the EventLoop's catch-and-warn guard let the
 * loop limp on with the leak.
 *
 * Two behaviours are pinned:
 * 1. **Drain succeeds** — a single submit frees a slot, the retry succeeds,
 *    the operation proceeds and completes with no leak.
 * 2. **Drain frees nothing (bounded)** — if the ring is still full after the
 *    submit (a wedged kernel, e.g. a full CQ ring), `acquireSqe` throws after
 *    exactly one drain attempt. It is a single retry, never a loop:
 *    `submitCalls == 1` proves there is no unbounded spin.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringSubmitDrainRetrySeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringSubmitDrainRetrySeamTest")

    private fun withTransport(
        nativeSocket: FakeNativeSocket,
        allocator: BufferAllocator,
        block: (FakeIoUringRing, IoUringEventLoop, IoUringIoTransport) -> Unit,
    ) {
        val fake = FakeIoUringRing()
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val bufRing =
            ProvidedBufferRing(el, logger, bufferCount = 4, bufferSize = 64, bgid = 0, FakeIoUringBufferRingOps())
        bufRing.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = 999,
            eventLoop = el,
            capabilities = IoUringCapabilities(),
            writeModeSelector = IoModeSelectors.FALLBACK_CQE,
            allocator = allocator,
            bufferRing = bufRing,
            fixedFileRegistry = null,
            registeredBufferTable = DisabledRegisteredBufferRegistry,
            preAllocatedIndex = -1,
            nativeSocket = nativeSocket,
        )
        try {
            block(fake, el, transport)
        } finally {
            bufRing.close()
            el.close()
            fake.dispose()
        }
    }

    private fun filledBuf(allocator: BufferAllocator, size: Int = 16): IoBuf {
        val buf = allocator.allocate(size)
        for (i in 0 until size) buf.writeByte(i.toByte())
        return buf
    }

    @Test
    fun `SQ ring full drains once via submit then the retried getSqe serves the op`() {
        val tracking = TrackingAllocator(DefaultAllocator)
        val fakeSocket = FakeNativeSocket()
        // send() would block → the buffer goes to an async SEND SQE, which is
        // where getSqe (and thus acquireSqe) runs.
        fakeSocket.enqueueSend(999, WriteResult.WouldBlock)
        withTransport(fakeSocket, tracking) { fake, el, transport ->
            var flushCompletions = 0
            transport.onFlushComplete = { flushCompletions++ }
            // The async SEND SQE's getSqe finds the ring full exactly once.
            fake.scriptSqRingFull()

            transport.write(filledBuf(tracking))
            transport.flush()

            assertEquals(1, fake.submitCalls, "acquireSqe must submit-drain once when the SQ ring is full")

            // The drained SQE now carries the send; completing its CQE drains the flush.
            fake.enqueueCqe(userData = fake.lastSqeUserData(), res = 16, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, flushCompletions, "flush completes once the drained SQE's CQE arrives")
        }
        assertEquals(0, tracking.outstandingCount, "the buffer is released — no leak on the drain path")
    }

    @Test
    fun `SQ ring still full after the drain throws once and does not spin`() {
        val fakeSocket = FakeNativeSocket()
        fakeSocket.enqueueSend(999, WriteResult.WouldBlock)
        withTransport(fakeSocket, DefaultAllocator) { fake, _, transport ->
            // Both the initial getSqe and the post-drain retry find the ring
            // full → the wedged-kernel path.
            fake.scriptSqRingFull()
            fake.scriptSqRingFull()

            transport.write(filledBuf(DefaultAllocator))
            assertFailsWith<IllegalStateException>("a wedged ring must fail fast, not hang") {
                transport.flush()
            }
            assertEquals(
                1,
                fake.submitCalls,
                "exactly one drain attempt — a single bounded retry, never an unbounded spin",
            )
        }
    }
}
