package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.set
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for the copy-on-pressure recv delivery.
 *
 * The provided buffer ring is shared by every connection on an EventLoop,
 * and a delivered slot stays out of the ring while the consumer references
 * it — with the HTTP codec retaining recv buffers for header views, the
 * request's whole latency. Enough simultaneously pinned slots used to stall
 * every connection on the loop (`-ENOBUFS`, deferred re-arm that never
 * fires while the pins persist). Under pressure (fewer than 25% of the
 * slots remaining) the transport now delivers an allocator-owned copy and
 * returns the slot immediately, so a retaining consumer can pin at most
 * `bufferCount - watermark` slots and the loop keeps receiving.
 *
 * Observables: the fake ring-ops `addCalls` records the slot return (the
 * copy path returns it during delivery; the zero-copy path only when the
 * consumer releases), payload bytes prove the copy is faithful, and the
 * ring's `copyOnPressureCount` counts the degraded deliveries.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringTransportCopyOnPressureSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringTransportCopyOnPressureSeamTest")

    /**
     * Fake-backed EventLoop + real [ProvidedBufferRing] (8 slots, so the
     * 25% pressure watermark is 2) + transport with multishot recv.
     */
    private fun withTransport(
        bufferCount: Int = 8,
        block:
        (FakeIoUringRing, FakeIoUringBufferRingOps, IoUringEventLoop, ProvidedBufferRing, IoUringIoTransport) -> Unit,
    ) {
        val fake = FakeIoUringRing()
        val bufRingFake = FakeIoUringBufferRingOps()
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        val bufRing = ProvidedBufferRing(el, logger, bufferCount, bufferSize = 64, bgid = 0, bufRingFake)
        bufRing.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = 999,
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
            block(fake, bufRingFake, el, bufRing, transport)
        } finally {
            bufRing.close()
            el.close()
        }
    }

    /** Encodes a recv data CQE's flags: `IORING_CQE_F_BUFFER` + buffer ID in the upper bits. */
    private fun bufFlags(bid: Int): UInt = (bid.toUInt() shl 16) or 1u

    /** Writes [payload] into ring slot [bid] so a copy delivery has bytes to prove itself with. */
    private fun writePayload(ring: ProvidedBufferRing, bid: Int, payload: ByteArray) {
        val ptr = ring.getPointer(bid)
        for (i in payload.indices) ptr[i] = payload[i]
    }

    @Test
    fun `above the watermark the slot-backed wrapper is delivered and the slot stays out until release`() {
        withTransport { fake, bufRingFake, el, bufRing, transport ->
            var delivered: IoBuf? = null
            transport.onRead = { delivered = it }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()
            val addsBefore = bufRingFake.addCalls.size

            // Fresh ring (8 available, watermark 2): zero-copy delivery.
            fake.enqueueCqe(userData = recvUserData, res = 5, flags = bufFlags(0), hasMore = true)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(0L, bufRing.copyOnPressureCount(), "no pressure on a fresh ring")
            assertEquals(
                addsBefore,
                bufRingFake.addCalls.size,
                "the zero-copy path must NOT return the slot during delivery",
            )

            // The consumer's release is what returns the slot.
            delivered!!.release()
            assertEquals(
                addsBefore + 1,
                bufRingFake.addCalls.size,
                "the consumer's release returns the slot",
            )
        }
    }

    @Test
    fun `under the watermark the delivery is a faithful allocator copy and the slot returns immediately`() {
        withTransport { fake, bufRingFake, el, bufRing, transport ->
            var delivered: IoBuf? = null
            transport.onRead = { delivered = it }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()

            // Drain to below the watermark: 8 -> 1 available (watermark 2).
            repeat(7) { bufRing.onConsumed() }

            val payload = byteArrayOf(0x6b, 0x65, 0x65, 0x6c, 0x21) // "keel!"
            writePayload(bufRing, bid = 3, payload = payload)
            val addsBefore = bufRingFake.addCalls.size

            fake.enqueueCqe(userData = recvUserData, res = payload.size, flags = bufFlags(3), hasMore = true)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(1L, bufRing.copyOnPressureCount(), "the delivery must be counted as copy-on-pressure")
            assertEquals(
                addsBefore + 1,
                bufRingFake.addCalls.size,
                "the copy path must return the slot during delivery, before the consumer releases",
            )
            val buf = delivered!!
            assertEquals(payload.size, buf.readableBytes)
            val copied = ByteArray(payload.size)
            buf.readByteArray(copied, 0, payload.size)
            assertTrue(payload.contentEquals(copied), "the copy must carry the slot's bytes")

            // Releasing the delivered copy is an allocator release — it must
            // not return any ring slot a second time.
            buf.release()
            assertEquals(
                addsBefore + 1,
                bufRingFake.addCalls.size,
                "the consumer release touches the allocator, not the ring",
            )
        }
    }

    @Test
    fun `the pressure boundary is strict - exactly at the watermark stays zero-copy`() {
        withTransport { fake, bufRingFake, el, bufRing, transport ->
            var delivered: IoBuf? = null
            transport.onRead = { delivered = it }
            transport.readEnabled = true
            val recvUserData = fake.lastSqeUserData()

            // Drain so the delivery's own consume lands exactly AT the
            // watermark: 8 -> 3, then the CQE consume makes available = 2
            // (watermark 2, predicate is strict less-than) -> zero-copy.
            repeat(5) { bufRing.onConsumed() }
            val addsBefore = bufRingFake.addCalls.size

            fake.enqueueCqe(userData = recvUserData, res = 4, flags = bufFlags(1), hasMore = true)
            assertTrue(el.runIteration(Cqe()))

            assertEquals(0L, bufRing.copyOnPressureCount(), "available == watermark is not yet pressure")
            assertEquals(addsBefore, bufRingFake.addCalls.size, "zero-copy: slot not returned during delivery")
            delivered!!.release()

            // One more consume pushes the next delivery below the watermark.
            bufRing.onConsumed() // 3 -> 2 (after the return above: 2+1=3, now 2)
            fake.enqueueCqe(userData = recvUserData, res = 4, flags = bufFlags(2), hasMore = true)
            assertTrue(el.runIteration(Cqe()))
            assertEquals(1L, bufRing.copyOnPressureCount(), "one below the watermark switches to copy")
        }
    }
}
