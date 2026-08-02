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
import kotlin.test.assertTrue

/**
 * Seam-level regression test for the FALLBACK_CQE full-`EAGAIN` gather path
 * (`flushDirectSendGather` → `submitAsyncSendChain`).
 *
 * `submitAsyncSendChain` previously indexed the LIVE `pendingWrites` list,
 * which `flush()` clears the instant the gather returns. On a full `EAGAIN`
 * (`writev` wrote 0 bytes) with multiple buffers, only the first buffer (read
 * synchronously) was sent; the rest were dropped and leaked — the same
 * live-list bug fixed for SEND_ZC in #935, latent here because a full-`EAGAIN`
 * gather is rare on loopback and the raw `keel_writev` call was invisible to
 * [FakeNativeSocket]. Routing the gather through the [NativeSocket] seam (as
 * epoll / kqueue already do) makes it scriptable: force `writev` → WouldBlock
 * and drive the async SEND chain one CQE at a time.
 *
 * The pre-fix double-count of `asyncPendingFlushBytes` (credited once at the
 * gather call site, then again per buffer in the chain) is fixed with the same
 * change and covered by construction — the chain no longer credits per buffer;
 * `pendingBytes` is `protected`, so this test pins the observable effects
 * (every buffer delivered, exactly one flush completion, zero leaked buffers).
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportFallbackGatherSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportFallbackGatherSeamTest")

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
    fun `full-EAGAIN gather sends every buffer via the async SEND chain without leaking`() {
        val tracking = TrackingAllocator(DefaultAllocator)
        val fakeSocket = FakeNativeSocket()
        // writev writes nothing (full EAGAIN) → all three buffers route to the
        // async SEND chain.
        fakeSocket.enqueueWritev(999, WriteResult.WouldBlock)
        withTransport(fakeSocket, tracking) { fake, el, transport ->
            var flushCompletions = 0
            transport.onFlushComplete = { flushCompletions++ }

            transport.write(filledBuf(tracking))
            transport.write(filledBuf(tracking))
            transport.write(filledBuf(tracking))
            transport.flush()

            assertEquals(1, fakeSocket.writevCalls, "the gather must go through the writev seam exactly once")

            // buf0's SEND SQE completes fully → chain advances to buf1.
            fake.enqueueCqe(userData = fake.lastSqeUserData(), res = 16, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(
                0,
                flushCompletions,
                "chain must still be mid-flight after buf0 (pre-fix drops buf1/buf2 here)",
            )

            // buf1 completes → chain advances to buf2.
            fake.enqueueCqe(userData = fake.lastSqeUserData(), res = 16, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(0, flushCompletions, "chain must still be mid-flight after buf1")

            // buf2 completes → chain drains → flush completes exactly once.
            fake.enqueueCqe(userData = fake.lastSqeUserData(), res = 16, flags = 0u, hasMore = false)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1, flushCompletions, "flush completes exactly once after all three buffers drain")
        }
        assertEquals(0, tracking.outstandingCount, "every buffer released — no dropped/leaked buffer")
    }
}
