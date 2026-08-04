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
    private fun withEventLoop(
        ring: FakeIoUringRing,
        ringSize: Int = IoUringEventLoop.DEFAULT_RING_SIZE,
        block: (IoUringEventLoop) -> Unit,
    ) {
        val el = IoUringEventLoop(
            logger,
            ringSize = ringSize,
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
    fun `submitCallback drains the SQ ring and retries when it is full`() {
        // acquireSqe: getSqe null -> submit-drain -> retry succeeds.
        val fake = FakeIoUringRing().apply { scriptSqRingFull() }
        withEventLoop(fake) { el ->
            val slot = el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            assertTrue(slot >= 0, "after a submit-drain the retried getSqe must serve the op, got $slot")
            assertEquals(1, fake.submitCalls, "exactly one submit-drain on a full ring")
        }
    }

    @Test
    fun `submitCallback throws once when the ring is still full after a drain`() {
        // Both getSqe calls (initial + post-drain retry) find the ring full:
        // a wedged kernel. acquireSqe throws after one bounded drain — no spin.
        val fake = FakeIoUringRing().apply {
            scriptSqRingFull()
            scriptSqRingFull()
        }
        withEventLoop(fake) { el ->
            val ex = assertFailsWith<IllegalStateException> {
                el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            }
            assertTrue(
                ex.message!!.contains("io_uring SQ ring full"),
                "message should mention the full SQ ring, got: ${ex.message}",
            )
            assertEquals(1, fake.submitCalls, "single bounded drain attempt — no retry loop")
        }
    }

    @Test
    fun `submitCallback fails fast on slot exhaustion without taking an SQE`() {
        // Regression: the submit helpers reserve the continuation/callback slot
        // BEFORE acquiring the SQE. If the slot pool is exhausted, acquireSlot
        // must throw before any getSqe — otherwise a prepared SQE would be left
        // in the ring with an unset (stale) user_data and flushed to the kernel,
        // routing its CQE to the wrong slot. Pin that no getSqe happens once the
        // pool is drained (before the fix, acquireSqe/getSqe ran, then acquireSlot
        // threw, leaving the half-prepared SQE — so getSqeCalls would be 3 here).
        val fake = FakeIoUringRing()
        withEventLoop(fake, ringSize = 2) { el ->
            el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            assertEquals(2, fake.getSqeCalls, "two slots consumed, two SQEs taken")

            val ex = assertFailsWith<IllegalStateException> {
                el.submitCallback(prepare = { }, onCqe = { _, _ -> })
            }
            assertTrue(
                ex.message!!.contains("slot pool exhausted"),
                "message should name the exhausted slot pool, got: ${ex.message}",
            )
            assertEquals(
                2,
                fake.getSqeCalls,
                "slot exhaustion must throw before acquireSqe — no SQE taken for the failed op",
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
    fun `submitMultishot drains the SQ ring and retries when it is full`() {
        val fake = FakeIoUringRing().apply { scriptSqRingFull() }
        withEventLoop(fake) { el ->
            val slot = el.submitMultishot(prepare = { }, onCqe = { _, _ -> })
            assertTrue(slot >= 0, "after a submit-drain the retried getSqe must serve the multishot op, got $slot")
            assertEquals(1, fake.submitCalls, "exactly one submit-drain on a full ring")
        }
    }

    // --- cancelSqe ---

    // --- lastSqeOp (op-kind recording) ---

    @Test
    fun `lastSqeOp returns IORING_OP_ACCEPT after accept prep`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            el.submitCallback(
                prepare = { sqe -> io_uring_prep_accept(sqe, fd = 3, addr = null, addrlen = null, flags = 0) },
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
                prepare = { sqe -> keel_prep_recv_multishot(sqe, sockfd = 4, bgid = 1u.toUShort()) },
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
                prepare = { sqe -> keel_prep_poll_add(sqe, fd = 5, poll_mask = 0x2000u) },
                onCqe = { _, _ -> },
            )
            assertEquals(IORING_OP_POLL_ADD, fake.lastSqeOp(), "opcode after poll prep")
        }
    }

    // --- lastPollSqeMask (poll mask recording) ---

    @Test
    fun `lastPollSqeMask returns the mask written by keel_prep_poll_add`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            // Representative peer-FIN watch mask.
            val mask: UInt = 0x2000u or 0x0010u or 0x0008u // POLLRDHUP | POLLHUP | POLLERR
            el.submitCallback(
                prepare = { sqe -> keel_prep_poll_add(sqe, fd = 5, poll_mask = mask) },
                onCqe = { _, _ -> },
            )
            assertEquals(IORING_OP_POLL_ADD, fake.lastSqeOp())
            assertEquals(mask, fake.lastPollSqeMask(), "poll mask after poll prep")
        }
    }

    // --- submitMultishotRecv SQ-ring-full coverage ---

    @Test
    fun `submitMultishotRecv drains the SQ ring and retries when it is full`() {
        // Parallel to the submitCallback / submitMultishot coverage: the
        // recv-specific multishot variant goes through the same acquireSqe
        // submit-drain path. Pin it explicitly so a future refactor that
        // touches the recv submit cannot drop the drain without the seam
        // catching it.
        val fake = FakeIoUringRing().apply { scriptSqRingFull() }
        withEventLoop(fake) { el ->
            el.submitMultishotRecv(fd = 5, bgid = 0) { _, _ -> }
            assertEquals(1, fake.submitCalls, "the recv submit drains once on a full ring, matching submitCallback")
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
