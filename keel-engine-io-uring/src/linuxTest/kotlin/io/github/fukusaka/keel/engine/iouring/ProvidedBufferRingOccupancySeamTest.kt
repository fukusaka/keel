package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam-level unit tests for [ProvidedBufferRing]'s occupancy observability
 * counters (min-available low watermark / recv `-ENOBUFS` count / deferred
 * re-arm count).
 *
 * Ring slots are pinned for a request's whole latency when the codec retains
 * the recv buffer (header byte-range views), so the low watermark is the
 * signal for how close a workload comes to the cross-connection `-ENOBUFS`
 * recv stall — and the measured basis for the copy-on-pressure threshold.
 * These tests pin the counter bookkeeping itself; the transport wiring of
 * [ProvidedBufferRing.onRecvEnobufs] is pinned in
 * [IoUringTransportRecvStarvationSeamTest].
 *
 * The ring is driven pre-`start()`, where the EventLoop thread assertion
 * no-ops, so the tests run synchronously — no timeout needed.
 */
@OptIn(ExperimentalForeignApi::class)
class ProvidedBufferRingOccupancySeamTest {

    private val logger = NoopLoggerFactory.logger("ProvidedBufferRingOccupancySeamTest")

    private fun withRing(
        bufferCount: Int = 4,
        block: (ProvidedBufferRing) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps())
        val ring = ProvidedBufferRing(el, logger, bufferCount, bufferSize = 64, bgid = 0, FakeIoUringBufferRingOps())
        try {
            ring.initOnEventLoop()
            block(ring)
        } finally {
            ring.close()
            el.close()
        }
    }

    @Test
    fun `the low watermark records the lowest occupancy and does not rise on returns`() {
        withRing(bufferCount = 4) { ring ->
            assertEquals(4, ring.minAvailableLowWatermark(), "fresh ring starts at full occupancy")

            // Consume 3 of 4: occupancy bottoms out at 1.
            repeat(3) { ring.onConsumed() }
            assertEquals(1, ring.minAvailableLowWatermark())

            // Returns raise the live occupancy but must not raise the watermark.
            ring.returnBuffer(0)
            ring.returnBuffer(1)
            assertEquals(1, ring.minAvailableLowWatermark(), "watermark is a historical minimum")

            // A later shallower dip (3 -> 2) does not move the recorded minimum.
            ring.onConsumed()
            assertEquals(1, ring.minAvailableLowWatermark())
        }
    }

    @Test
    fun `a full drain records a zero watermark`() {
        withRing(bufferCount = 4) { ring ->
            repeat(4) { ring.onConsumed() }
            assertEquals(0, ring.minAvailableLowWatermark(), "fully drained ring must record zero")
        }
    }

    @Test
    fun `enobufs and deferred re-arm counters accumulate independently`() {
        withRing(bufferCount = 4) { ring ->
            assertEquals(0L, ring.recvEnobufsCount())
            assertEquals(0L, ring.deferredRearmCount())

            // Two starvation CQEs, of which only one ends up deferring
            // (the other re-arms immediately within its batch).
            ring.onRecvEnobufs()
            ring.onRecvEnobufs()
            ring.requestRearmOnAvailable { }

            assertEquals(2L, ring.recvEnobufsCount())
            assertEquals(1L, ring.deferredRearmCount())

            // Draining the deferral via returnBuffer does not change either
            // counter — they are cumulative episode counts, not live gauges.
            ring.onConsumed()
            ring.returnBuffer(0)
            assertEquals(2L, ring.recvEnobufsCount())
            assertEquals(1L, ring.deferredRearmCount())
        }
    }
}
