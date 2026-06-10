package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level unit tests pinning the `res < 0` error propagation in
 * `IoUringIoTransport`'s async write callbacks — the
 * `flushWritev` / `submitAsyncSendZcSequential` (Fixed + non-Fixed) /
 * `flushSendmsgZc` paths that previously silently released their
 * buffers and called `onComplete` on a kernel error CQE, leaving the
 * pipeline convinced the bytes had landed and the orphaned transport
 * alive.
 *
 * The `submitAsyncSendSequential` (non-ZC) callback already had this
 * propagation (added on review of an earlier integration regression
 * — see the inline `Surface async send errors` comment in production).
 * The audit follow-up that ran after PR #745 noticed the four sibling
 * write paths had drifted away from the same fix: each error CQE
 * (`-EPIPE` / `-ECONNRESET` / etc.) ended in `onAsyncFlushDone()` or
 * `onComplete()` without calling `fireReadClosedOnce`, so the pipeline
 * never learned that the connection was broken.
 *
 * One test per fixed path. Each test forces the corresponding
 * [IoMode] via [IoModeSelectors], submits a write through the
 * transport's public `write` + `flush` API, intercepts the SQE via
 * the fake ring, scripts a `-EPIPE` CQE, and asserts that
 * `onReadClosed` fired exactly once.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportWriteErrorSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportWriteErrorSeamTest")

    /**
     * Builds an [IoUringIoTransport] over a fake-backed [IoUringEventLoop]
     * + initialised [ProvidedBufferRing]. [writeModeSelector] forces the
     * async path to a specific [IoMode]; [capabilities] is wired to allow
     * the chosen mode (the production `flush` would otherwise degrade to
     * [IoMode.CQE] for SEND_ZC / SENDMSG_ZC).
     */
    private fun withTransport(
        fake: FakeIoUringRing = FakeIoUringRing(),
        writeModeSelector: IoModeSelector,
        capabilities: IoUringCapabilities,
        block: (FakeIoUringRing, IoUringEventLoop, IoUringIoTransport) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val bufRing = ProvidedBufferRing(el, logger, bufferCount = 4, bufferSize = 64, bgid = 0, FakeIoUringBufferRingOps())
        bufRing.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = 999,
            eventLoop = el,
            capabilities = capabilities,
            writeModeSelector = writeModeSelector,
            allocator = DefaultAllocator,
            bufferRing = bufRing,
            fixedFileRegistry = null,
            registeredBufferTable = null,
            preAllocatedIndex = -1,
        )
        try {
            block(fake, el, transport)
        } finally {
            bufRing.close()
            el.close()
        }
    }

    /** Allocates a small filled buffer to queue on the transport. */
    private fun smallFilledBuf(size: Int = 16): IoBuf {
        val buf = DefaultAllocator.allocate(size)
        for (i in 0 until size) buf.writeByte(i.toByte())
        return buf
    }

    @Test
    fun `CQE writev error CQE fires onReadClosed via fireReadClosedOnce`() {
        // Two pending writes route through flushCqe → submitAsyncWritev,
        // which exercises the writev callback that previously treated
        // res < 0 as writtenBytes = 0 and fell into the partial-write
        // retry path (resubmitting the same payload forever / silent drop).
        withTransport(
            writeModeSelector = IoModeSelectors.CQE,
            capabilities = IoUringCapabilities(),
        ) { fake, el, transport ->
            var onReadClosedFires = 0
            transport.onReadClosed = { onReadClosedFires++ }

            transport.write(smallFilledBuf())
            transport.write(smallFilledBuf())
            transport.flush()

            val writevUserData = fake.lastSqeUserData()
            fake.enqueueCqe(userData = writevUserData, res = -EPIPE, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(1, onReadClosedFires, "writev -EPIPE must fire onReadClosed via fireReadClosedOnce")
        }
    }

    @Test
    fun `SEND_ZC error CQE fires onReadClosed via fireReadClosedOnce`() {
        // SEND_ZC mode submits one SEND_ZC SQE per buffer. The first CQE
        // carries the send result (here -EPIPE); with hasMore=0 the engine
        // completes the slot immediately, so the transport's callback
        // sees res < 0 and must call fireReadClosedOnce.
        withTransport(
            writeModeSelector = IoModeSelectors.SEND_ZC,
            capabilities = IoUringCapabilities(sendZc = true),
        ) { fake, el, transport ->
            var onReadClosedFires = 0
            transport.onReadClosed = { onReadClosedFires++ }

            transport.write(smallFilledBuf())
            transport.flush()

            val sendZcUserData = fake.lastSqeUserData()
            // hasMore=0 collapses the 2-CQE protocol — the engine fires
            // completeZcSlot with -EPIPE on the first CQE.
            fake.enqueueCqe(userData = sendZcUserData, res = -EPIPE, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(1, onReadClosedFires, "SEND_ZC -EPIPE must fire onReadClosed via fireReadClosedOnce")
        }
    }

    @Test
    fun `SENDMSG_ZC error CQE fires onReadClosed via fireReadClosedOnce`() {
        // SENDMSG_ZC needs two or more pending writes (single-buffer flush
        // falls back to SEND_ZC). Same single-CQE collapse via hasMore=0.
        withTransport(
            writeModeSelector = IoModeSelectors.SENDMSG_ZC,
            capabilities = IoUringCapabilities(sendZc = true, sendmsgZc = true),
        ) { fake, el, transport ->
            var onReadClosedFires = 0
            transport.onReadClosed = { onReadClosedFires++ }

            transport.write(smallFilledBuf())
            transport.write(smallFilledBuf())
            transport.flush()

            val sendmsgZcUserData = fake.lastSqeUserData()
            fake.enqueueCqe(userData = sendmsgZcUserData, res = -EPIPE, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(1, onReadClosedFires, "SENDMSG_ZC -EPIPE must fire onReadClosed via fireReadClosedOnce")
        }
    }
}
