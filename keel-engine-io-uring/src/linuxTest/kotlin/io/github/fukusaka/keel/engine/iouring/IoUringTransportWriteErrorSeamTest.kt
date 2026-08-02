package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.AF_INET
import platform.posix.EPIPE
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.socket
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
        val bufRing =
            ProvidedBufferRing(el, logger, bufferCount = 4, bufferSize = 64, bgid = 0, FakeIoUringBufferRingOps())
        bufRing.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = 999,
            eventLoop = el,
            capabilities = capabilities,
            writeModeSelector = writeModeSelector,
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

    @Test
    fun `direct gather writev unrecoverable error fires onReadClosed via fireReadClosedOnce`() {
        // Deep-audit follow-up (F-1): `flushDirectSendGather` (the FALLBACK_CQE
        // synchronous direct-writev path) previously released buffers and
        // returned `true` to the pipeline on an unrecoverable -EBADF / -EPIPE /
        // -ECONNRESET, leaving the orphaned transport alive — same shape as
        // the four async callbacks fixed in PR #746 (writev / SEND_ZC / SEND_ZC
        // Fixed / SENDMSG_ZC).
        //
        // The `flushDirectSendGather` body calls `keel_writev(fd, ...)` directly
        // (bypassing NativeSocket so FakeNativeSocket cannot intercept it).
        // Drive the error path with a real-but-closed fd: writev(closedFd,
        // iovec, count) returns -1 / EBADF, hitting the unrecoverable branch.
        val ring = FakeIoUringRing()
        val bufRingFake = FakeIoUringBufferRingOps()
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = ring)
        val bufRing = ProvidedBufferRing(el, logger, 4, 64, 0, bufRingFake)
        bufRing.initOnEventLoop()

        // Open a real socket fd so the transport's init doesn't trip on -1;
        // close it immediately so the subsequent writev returns -EBADF.
        val closedFd = socket(AF_INET, SOCK_STREAM, 0)
        check(closedFd >= 0) { "socket() failed in test setUp" }
        close(closedFd)

        val transport = IoUringIoTransport(
            fd = closedFd,
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
            var onReadClosedFires = 0
            transport.onReadClosed = { onReadClosedFires++ }

            // Queue two writes so flushDirectSend routes through flushDirectSendGather
            // (single-write flushes go through flushDirectSendSingle).
            transport.write(smallFilledBuf())
            transport.write(smallFilledBuf())
            transport.flush()

            assertEquals(
                1,
                onReadClosedFires,
                "direct gather writev unrecoverable error must fire onReadClosed via fireReadClosedOnce",
            )
        } finally {
            bufRing.close()
            el.close()
            ring.dispose()
        }
    }
}
