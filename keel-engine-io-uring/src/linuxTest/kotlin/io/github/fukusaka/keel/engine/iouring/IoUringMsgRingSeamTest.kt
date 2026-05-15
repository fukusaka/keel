package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for the `IORING_OP_MSG_RING` cross-ring wakeup
 * path — [IoUringEventLoop.submitMsgRingTo]'s fallback branches and the
 * `runIteration` dispatch of the MSG_RING completion tokens.
 *
 * Part of the io_uring native API seam effort. Both surfaces are
 * driven on the test thread: `submitMsgRingTo` issues no thread-affinity
 * assertion before `start()`, and `runIteration` never did — no
 * EventLoop pthread, no timeout needed.
 *
 * The MSG_RING *success* path (`keel_prep_msg_ring` + a positive target
 * `ringFd`) is not exercised here: `ringFd` is populated only on the
 * target's own pthread inside `loop()`, so a pre-`start()` target
 * always reports the not-initialised fallback.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringMsgRingSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringMsgRingSeamTest")

    private fun newEventLoop(ring: FakeIoUringRing): IoUringEventLoop =
        IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = ring)

    /** Single pre-`start()` EventLoop for the CQE-dispatch tests. */
    private fun withEventLoop(ring: FakeIoUringRing, block: (IoUringEventLoop) -> Unit) {
        val el = newEventLoop(ring)
        try {
            block(el)
        } finally {
            el.close()
            ring.dispose()
        }
    }

    /** Source + target EventLoop pair for the [IoUringEventLoop.submitMsgRingTo] tests. */
    private fun withSourceAndTarget(
        sourceRing: FakeIoUringRing,
        block: (source: IoUringEventLoop, target: IoUringEventLoop) -> Unit,
    ) {
        val targetRing = FakeIoUringRing()
        val source = newEventLoop(sourceRing)
        val target = newEventLoop(targetRing)
        try {
            block(source, target)
        } finally {
            source.close()
            target.close()
            sourceRing.dispose()
            targetRing.dispose()
        }
    }

    // --- submitMsgRingTo fallback branches ---

    @Test
    fun `submitMsgRingTo returns false when the source SQ ring is full`() {
        val sourceRing = FakeIoUringRing().apply { scriptSqRingFull() }
        withSourceAndTarget(sourceRing) { source, target ->
            assertFalse(
                source.submitMsgRingTo(target),
                "a full source SQ ring must fall back to the eventfd wakeup path",
            )
        }
    }

    @Test
    fun `submitMsgRingTo returns false when the target ring is not yet initialised`() {
        // The target was never start()ed, so its ringFd is still -1.
        withSourceAndTarget(FakeIoUringRing()) { source, target ->
            assertFalse(
                source.submitMsgRingTo(target),
                "an uninitialised target ring fd must fall back to the eventfd wakeup path",
            )
        }
    }

    // --- runIteration MSG_RING token dispatch ---

    @Test
    fun `runIteration discards a MSG_RING_WAKEUP_TOKEN CQE`() {
        val ring = FakeIoUringRing()
        withEventLoop(ring) { el ->
            // Target-side CQE: the task was already queued by the peer's
            // dispatch(); the CQE itself carries no work.
            ring.enqueueCqe(userData = IoUringEventLoop.MSG_RING_WAKEUP_TOKEN, res = 0)
            assertTrue(el.runIteration(Cqe()))
        }
    }

    @Test
    fun `runIteration discards a MSG_RING_SEND_TOKEN CQE`() {
        val ring = FakeIoUringRing()
        withEventLoop(ring) { el ->
            // Source-side completion of a successful cross-ring send.
            ring.enqueueCqe(userData = IoUringEventLoop.MSG_RING_SEND_TOKEN, res = 0)
            assertTrue(el.runIteration(Cqe()))
        }
    }

    @Test
    fun `runIteration discards a failed MSG_RING_SEND_TOKEN CQE`() {
        val ring = FakeIoUringRing()
        withEventLoop(ring) { el ->
            // res < 0: the target ring was closed / unreachable. Benign at
            // shutdown — debug-logged and discarded, the loop continues.
            ring.enqueueCqe(userData = IoUringEventLoop.MSG_RING_SEND_TOKEN, res = -9)
            assertTrue(el.runIteration(Cqe()))
        }
    }
}
