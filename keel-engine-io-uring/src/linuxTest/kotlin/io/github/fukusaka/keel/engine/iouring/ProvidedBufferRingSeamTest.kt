package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EINVAL
import platform.posix.ENOMEM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
