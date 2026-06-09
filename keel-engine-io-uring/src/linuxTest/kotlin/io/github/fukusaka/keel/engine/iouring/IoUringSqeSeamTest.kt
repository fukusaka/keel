package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io_uring.io_uring_prep_accept
import io_uring.keel_prep_poll_add
import io_uring.keel_prep_recv_multishot
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [IoUringEventLoop]'s SQE acquisition via
 * [FakeIoUringRing] injection. Covers the SQ-ring-full branch
 * (`io_uring_get_sqe` returns `null`) — only reachable when every
 * submission queue entry is in flight, which a loopback integration
 * test never sustains — across the fire-and-forget submit paths.
 *
 * Part of the io_uring native API seam effort. The submit methods
 * exercised here ([IoUringEventLoop.submitCallback] /
 * [IoUringEventLoop.submitMultishot] / [IoUringEventLoop.cancelSqe])
 * are non-suspending and issue no thread-affinity assertion, so the
 * tests drive them directly on the test thread without spawning the
 * EventLoop pthread — no timeout needed.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringSqeSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringSqeSeamTest")

    /**
     * Builds an [IoUringEventLoop] backed by [ring], runs [block], then
     * tears the loop down and frees the fake's scratch-SQE arena.
     */
    private fun withEventLoop(ring: FakeIoUringRing, block: (IoUringEventLoop) -> Unit) {
        val el = IoUringEventLoop(
            logger,
            syscallOps = FakeIoUringSyscallOps(),
            ioUringRing = ring,
        )
        try {
            block(el)
        } finally {
            el.close()
            ring.dispose()
        }
    }

    // --- submitCallback ---

    @Test
    fun `submitCallback acquires a slot on the happy path`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            val slot = el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            assertTrue(slot >= 0, "a successful submit must return a non-negative slot, got $slot")
            assertEquals(1, fake.getSqeCalls)
        }
    }

    @Test
    fun `submitCallback throws when the SQ ring is full`() {
        val fake = FakeIoUringRing().apply { scriptSqRingFull() }
        withEventLoop(fake) { el ->
            val ex = assertFailsWith<IllegalStateException> {
                el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            }
            assertTrue(
                ex.message!!.contains("io_uring SQ ring full"),
                "message should mention the full SQ ring, got: ${ex.message}",
            )
        }
    }

    // --- submitMultishot ---

    @Test
    fun `submitMultishot acquires a slot on the happy path`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            val slot = el.submitMultishot(prepare = { }, onCqe = { _, _ -> })
            assertTrue(slot >= 0, "a successful multishot submit must return a non-negative slot, got $slot")
        }
    }

    @Test
    fun `submitMultishot throws when the SQ ring is full`() {
        val fake = FakeIoUringRing().apply { scriptSqRingFull() }
        withEventLoop(fake) { el ->
            val ex = assertFailsWith<IllegalStateException> {
                el.submitMultishot(prepare = { }, onCqe = { _, _ -> })
            }
            assertTrue(ex.message!!.contains("io_uring SQ ring full"), "got: ${ex.message}")
        }
    }

    // --- cancelSqe ---

    // --- lastSqeOp (op-kind recording) ---

    @Test
    fun `lastSqeOp returns IORING_OP_ACCEPT after accept prep`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            el.submitCallback(
                prepare = { sqe -> io_uring_prep_accept(sqe, /* fd */ 3, null, null, /* flags */ 0) },
                onCqe = { _, _ -> },
            )
            assertEquals(IORING_OP_ACCEPT, fake.lastSqeOp(), "opcode after accept prep")
        }
    }

    @Test
    fun `lastSqeOp returns IORING_OP_RECV after multishot recv prep`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            el.submitMultishot(
                prepare = { sqe -> keel_prep_recv_multishot(sqe, /* fd */ 4, /* bgid */ 1u.toUShort()) },
                onCqe = { _, _ -> },
            )
            assertEquals(IORING_OP_RECV, fake.lastSqeOp(), "opcode after multishot recv prep")
        }
    }

    @Test
    fun `lastSqeOp returns IORING_OP_POLL_ADD after poll prep`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            el.submitCallback(
                prepare = { sqe -> keel_prep_poll_add(sqe, /* fd */ 5, /* pollMask */ 0x2000u) },
                onCqe = { _, _ -> },
            )
            assertEquals(IORING_OP_POLL_ADD, fake.lastSqeOp(), "opcode after poll prep")
        }
    }

    @Test
    fun `cancelSqe on a full SQ ring is a silent no-op`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            // Acquire a real slot first so cancelSqe has a valid index.
            val slot = el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            // The cancel SQE cannot be obtained — cancelSqe must swallow this:
            // the original SQE completes on its own and frees the slot later.
            fake.scriptSqRingFull()
            el.cancelSqe(slot) // must not throw
        }
    }

    private companion object {
        // io_uring opcode values from `enum io_uring_op` in <linux/io_uring.h>.
        // The kernel ABI freezes these (existing entries are append-only with
        // stable indices), so hard-coding the well-known values keeps the test
        // independent of cinterop enum-exposure details.
        private const val IORING_OP_POLL_ADD: UByte = 6u
        private const val IORING_OP_ACCEPT: UByte = 13u
        private const val IORING_OP_RECV: UByte = 27u
    }
}
