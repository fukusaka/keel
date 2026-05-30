package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EINVAL
import platform.posix.ENOMEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [ProvidedBufferRing] via [FakeIoUringBufferRingOps]
 * injection. Covers the `io_uring_setup_buf_ring` / `_free_buf_ring`
 * failure branches — only reachable under real kernel pressure
 * (`ENOMEM` / `EINVAL`) — and verifies the buffer publish bookkeeping
 * (`addBuffer` / `advance` counts) that decides whether the kernel can
 * actually select buffers.
 *
 * Part of the io_uring native API seam effort. The ring is driven
 * pre-`start()`, where [IoUringEventLoop.assertInEventLoop] no-ops, so
 * the tests run synchronously on the test thread — no timeout needed.
 *
 * **Out of scope**: `-ENOBUFS` (kernel ran out of provided buffers)
 * arrives in a CQE and is handled on the CQE-drain side, not by
 * [ProvidedBufferRing] — it is not exercised here.
 */
@OptIn(ExperimentalForeignApi::class)
class ProvidedBufferRingSeamTest {

    private val logger = NoopLoggerFactory.logger("ProvidedBufferRingSeamTest")

    /**
     * Builds a [ProvidedBufferRing] backed by [fake] and a pre-`start()`
     * [IoUringEventLoop], runs [block], then tears both down (which frees
     * the ring's native buffer memory).
     */
    private fun withRing(
        fake: FakeIoUringBufferRingOps,
        bufferCount: Int = 4,
        bufferSize: Int = 64,
        block: (ProvidedBufferRing) -> Unit,
    ) {
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps())
        val ring = ProvidedBufferRing(el, logger, bufferCount, bufferSize, bgid = 0, fake)
        try {
            block(ring)
        } finally {
            ring.close()
            el.close()
        }
    }

    // --- initOnEventLoop ---

    @Test
    fun `initOnEventLoop stages every buffer and publishes them in one advance`() {
        val fake = FakeIoUringBufferRingOps()
        withRing(fake, bufferCount = 4) { ring ->
            ring.initOnEventLoop()
            assertEquals(1, fake.setupBufRingCalls)
            // Each of the 4 buffers staged at its own ring slot (bid == offset).
            assertEquals(
                listOf(
                    FakeIoUringBufferRingOps.AddCall(0, 0),
                    FakeIoUringBufferRingOps.AddCall(1, 1),
                    FakeIoUringBufferRingOps.AddCall(2, 2),
                    FakeIoUringBufferRingOps.AddCall(3, 3),
                ),
                fake.addCalls,
            )
            // A single advance publishes all 4 — a wrong count here would
            // leave buffers invisible to the kernel.
            assertEquals(listOf(4), fake.advanceCounts)
        }
    }

    @Test
    fun `initOnEventLoop throws when setup_buf_ring fails`() {
        val fake = FakeIoUringBufferRingOps().apply { scriptSetupFailure(ENOMEM) }
        withRing(fake) { ring ->
            val ex = assertFailsWith<IllegalStateException> { ring.initOnEventLoop() }
            assertTrue(
                ex.message!!.contains("io_uring_setup_buf_ring failed"),
                "message should mention setup_buf_ring failure, got: ${ex.message}",
            )
            // A failed setup must not stage any buffers.
            assertTrue(fake.addCalls.isEmpty())
            assertTrue(fake.advanceCounts.isEmpty())
        }
    }

    @Test
    fun `initOnEventLoop is idempotent`() {
        val fake = FakeIoUringBufferRingOps()
        withRing(fake) { ring ->
            ring.initOnEventLoop()
            ring.initOnEventLoop()
            assertEquals(1, fake.setupBufRingCalls, "second call must be a no-op once the ring exists")
        }
    }

    // --- returnBuffer ---

    @Test
    fun `returnBuffer stages one buffer and advances by one`() {
        val fake = FakeIoUringBufferRingOps()
        withRing(fake, bufferCount = 4) { ring ->
            ring.initOnEventLoop()
            ring.returnBuffer(bufId = 2)
            // returnBuffer always stages at ring offset 0 (the kernel tracks
            // the tail) — the recycled buffer keeps its own bid.
            assertEquals(FakeIoUringBufferRingOps.AddCall(2, 0), fake.addCalls.last())
            assertEquals(1, fake.advanceCounts.last())
        }
    }

    @Test
    fun `returnBuffer before init throws`() {
        val fake = FakeIoUringBufferRingOps()
        withRing(fake) { ring ->
            val ex = assertFailsWith<IllegalStateException> { ring.returnBuffer(bufId = 0) }
            assertTrue(ex.message!!.contains("not yet initialised"), "got: ${ex.message}")
        }
    }

    // --- deferred re-arm on buffer availability (K62) ---

    @Test
    fun `requestRearmOnAvailable fires once on the next buffer return`() {
        withRing(FakeIoUringBufferRingOps()) { ring ->
            ring.initOnEventLoop()
            var fired = 0
            ring.requestRearmOnAvailable { fired++ }
            ring.returnBuffer(bufId = 0)
            assertEquals(1, fired, "re-arm must fire when a buffer becomes available")
            // No re-arm is pending now, so a further return does not re-fire.
            ring.returnBuffer(bufId = 0)
            assertEquals(1, fired, "re-arm must fire at most once per registration")
        }
    }

    @Test
    fun `a single buffer return drains every pending re-arm`() {
        withRing(FakeIoUringBufferRingOps()) { ring ->
            ring.initOnEventLoop()
            var a = 0
            var b = 0
            // One shared ring serves all connections, so a single return must
            // re-arm every transport that gave up on -ENOBUFS.
            ring.requestRearmOnAvailable { a++ }
            ring.requestRearmOnAvailable { b++ }
            ring.returnBuffer(bufId = 1)
            assertEquals(1, a)
            assertEquals(1, b)
        }
    }

    @Test
    fun `a re-arm that re-registers waits for the next return instead of the current drain`() {
        withRing(FakeIoUringBufferRingOps()) { ring ->
            ring.initOnEventLoop()
            var fired = 0
            // A transport still starved after re-arming re-registers itself.
            // The snapshot-then-drain order in returnBuffer must keep that
            // re-registration out of the current drain (else it loops forever).
            lateinit var rearm: () -> Unit
            rearm = {
                fired++
                ring.requestRearmOnAvailable(rearm)
            }
            ring.requestRearmOnAvailable(rearm)
            ring.returnBuffer(bufId = 0)
            assertEquals(1, fired, "re-registration must defer to the next return")
            ring.returnBuffer(bufId = 0)
            assertEquals(2, fired)
        }
    }

    // --- buffer availability tracking (large-frame re-arm) ---

    @Test
    fun `hasAvailable tracks ring occupancy via onConsumed and returnBuffer`() {
        withRing(FakeIoUringBufferRingOps(), bufferCount = 4) { ring ->
            ring.initOnEventLoop()
            assertTrue(ring.hasAvailable, "all buffers available after init")
            repeat(4) { ring.onConsumed() }
            assertFalse(ring.hasAvailable, "ring empty after every buffer consumed")
            ring.returnBuffer(bufId = 0)
            assertTrue(ring.hasAvailable, "available again after a buffer is returned")
        }
    }

    @Test
    fun `returns within one CQE batch keep the ring non-empty at -ENOBUFS`() {
        // Models a single read delivery larger than the whole ring: the kernel
        // fills + reports every buffer and raises -ENOBUFS in one CQE batch, and
        // the app consumes + returns each buffer before the terminal -ENOBUFS CQE
        // is processed. The ring is therefore non-empty when -ENOBUFS arrives, so
        // the transport re-arms immediately instead of stalling (a deferred re-arm
        // would never fire — no later returnBuffer remains).
        withRing(FakeIoUringBufferRingOps(), bufferCount = 4) { ring ->
            ring.initOnEventLoop()
            repeat(4) { bid ->
                ring.onConsumed()
                ring.returnBuffer(bufId = bid)
            }
            assertTrue(ring.hasAvailable, "batch returns leave buffers in the ring")
        }
    }

    @Test
    fun `onConsumed clamps at zero so availability never drifts negative`() {
        withRing(FakeIoUringBufferRingOps(), bufferCount = 2) { ring ->
            ring.initOnEventLoop()
            repeat(5) { ring.onConsumed() } // more than bufferCount
            assertFalse(ring.hasAvailable)
            ring.returnBuffer(bufId = 0)
            assertTrue(ring.hasAvailable, "a single return restores availability (no negative drift)")
        }
    }

    // --- close ---

    @Test
    fun `close frees the ring`() {
        val fake = FakeIoUringBufferRingOps()
        withRing(fake) { ring ->
            ring.initOnEventLoop()
            ring.close()
            assertEquals(1, fake.freeBufRingCalls)
        }
    }

    @Test
    fun `close with free_buf_ring failure still clears the ring handle`() {
        val fake = FakeIoUringBufferRingOps()
        withRing(fake) { ring ->
            ring.initOnEventLoop()
            fake.scriptFreeFailure(EINVAL)
            ring.close()
            assertEquals(1, fake.freeBufRingCalls)
            // The handle must be cleared even when free_buf_ring fails:
            // returnBuffer now observes an uninitialised ring.
            assertFailsWith<IllegalStateException> { ring.returnBuffer(bufId = 0) }
        }
    }

    @Test
    fun `close before init does not call free_buf_ring`() {
        val fake = FakeIoUringBufferRingOps()
        withRing(fake) { ring ->
            ring.close()
            assertEquals(0, fake.freeBufRingCalls, "no ring was set up — nothing to free")
        }
    }
}
