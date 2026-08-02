package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level regression test for the multi-buffer partial-writev
 * remainder chain (`submitAsyncWritev` → `submitAsyncWritevRemainder` →
 * `submitAsyncWritevRemainderFrom`) after PR that routes its ownership
 * snapshot through [io.github.fukusaka.keel.pipeline.PendingWriteSnapshotPool]
 * instead of a fresh `ArrayList(pendingWrites)` per call.
 *
 * This recursive chain — split mid-buffer on a partial WRITEV CQE, then
 * complete the remaining buffers one SEND SQE at a time — was previously
 * uncovered by any success-path test (only the four sibling error-CQE
 * paths were pinned, in [IoUringTransportWriteErrorSeamTest]). It is the
 * path most exposed to a snapshot-pooling regression: the borrowed list
 * is threaded through three sequential async callbacks before recycling,
 * so an early recycle (double-recycle) or a lost `writes` reference
 * (leaked ownership) would either double-release an [IoBuf] — throwing
 * `IllegalStateException` per [IoBuf.release] — or leave a buffer
 * un-sent/un-released. [PendingWriteSnapshotPool]'s own bookkeeping
 * (free-list identity, no-alias-under-backpressure) is covered generically
 * by `PendingWriteSnapshotPoolTest` in `keel-core`; this test exercises the
 * call-site wiring specific to io_uring's multi-step chain.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportFlushSnapshotPoolSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportFlushSnapshotPoolSeamTest")

    private fun withTransport(
        fake: FakeIoUringRing = FakeIoUringRing(),
        block: (FakeIoUringRing, IoUringEventLoop, IoUringIoTransport) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val bufRing = ProvidedBufferRing(
            el,
            logger,
            bufferCount = 4,
            bufferSize = 64,
            bgid = 0,
            FakeIoUringBufferRingOps(),
        )
        bufRing.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = 999,
            eventLoop = el,
            capabilities = IoUringCapabilities(),
            writeModeSelector = IoModeSelectors.CQE,
            allocator = DefaultAllocator,
            bufferRing = bufRing,
            fixedFileRegistry = null,
            registeredBufferTable = DisabledRegisteredBufferRegistry,
            preAllocatedIndex = -1,
        )
        try {
            block(fake, el, transport)
        } finally {
            bufRing.close()
            el.close()
            fake.dispose()
        }
    }

    private fun filledBuf(size: Int = 16): IoBuf {
        val buf = DefaultAllocator.allocate(size)
        for (i in 0 until size) buf.writeByte(i.toByte())
        return buf
    }

    @Test
    fun `partial WRITEV CQE splits mid-buffer and remainder drains via chained SEND SQEs to a single flush completion`() {
        // 3 pending writes of 16 bytes each (48 total) route through
        // flushCqe -> submitAsyncWritev (WRITEV SQE, multi-buffer).
        withTransport { fake, el, transport ->
            var flushCompletions = 0
            var onReadClosedFires = 0
            transport.onFlushComplete = { flushCompletions++ }
            transport.onReadClosed = { onReadClosedFires++ }

            transport.write(filledBuf())
            transport.write(filledBuf())
            transport.write(filledBuf())
            transport.flush()

            // WRITEV completes buf0 fully (16) + 4 bytes into buf1 (20 total) ->
            // splitIndex=1, alreadySent=4 -> submitAsyncWritevRemainder submits a
            // SEND SQE for buf1's remaining 12 bytes.
            val writevUserData = fake.lastSqeUserData()
            fake.enqueueCqe(userData = writevUserData, res = 20, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(0, flushCompletions, "chain must still be mid-flight after the first CQE")

            // buf1's remainder (12 bytes) completes fully -> onComplete chains to
            // submitAsyncWritevRemainderFrom(writes, 2), submitting a SEND SQE for buf2.
            val buf1RemainderUserData = fake.lastSqeUserData()
            fake.enqueueCqe(userData = buf1RemainderUserData, res = 12, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(0, flushCompletions, "chain must still be mid-flight after the second CQE")

            // buf2 (16 bytes) completes fully -> startIndex(3) >= writes.size(3) ->
            // pool.recycle(writes) + onAsyncFlushDone -> onFlushComplete fires.
            val buf2UserData = fake.lastSqeUserData()
            fake.enqueueCqe(userData = buf2UserData, res = 16, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(1, flushCompletions, "flush must complete exactly once after the full chain drains")
            assertEquals(0, onReadClosedFires, "no error CQE was scripted; the connection must stay open")
        }
    }
}
