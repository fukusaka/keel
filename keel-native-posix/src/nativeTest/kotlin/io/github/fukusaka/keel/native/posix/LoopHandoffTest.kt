package io.github.fukusaka.keel.native.posix

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Unit tests for [LoopHandoff] — the off-loop to EventLoop hand-off the POSIX
 * readiness engines share.
 *
 * These need no real loop: the hand-off takes its two loop dependencies as
 * lambdas, so a test drives the queue by hand and decides when the loop is on
 * the caller's thread, when it has finished polling, and when its final drain
 * is complete. That is coverage the engines could only reach through a live
 * kqueue / epoll before this was extracted.
 *
 * Most of these never block: they either take the inline path or return at the
 * `loopFinished` guard. The one that does enter the quiesce spin drives it from
 * a second thread and carries a wall-clock bound, because a regression that
 * never publishes quiescence would otherwise hang rather than fail.
 */
@OptIn(InternalPosixEventLoopApi::class)
class LoopHandoffTest {

    /** Records dispatched tasks so a test can run them when it chooses. */
    private class FakeLoop {
        val queue = mutableListOf<() -> Unit>()
        var onLoopThread = false

        fun handoff(): LoopHandoff = LoopHandoff(
            inEventLoop = { onLoopThread },
            dispatchToLoop = { task -> queue.add(task) },
        )

        /** Runs everything queued, as the loop's drain would. */
        fun drain() {
            val pending = queue.toList()
            queue.clear()
            pending.forEach { it() }
        }
    }

    @Test
    fun `on the loop thread the work runs inline and nothing is queued`() {
        val loop = FakeLoop().apply { onLoopThread = true }
        val handoff = loop.handoff()
        var onLoop = 0
        var ifStopped = 0

        handoff.runOnLoop(onLoop = { onLoop++ }, ifStopped = { ifStopped++ })

        assertEquals(1, onLoop, "the caller already owns the loop, so it runs the work itself")
        assertEquals(0, ifStopped)
        assertTrue(loop.queue.isEmpty(), "nothing may be queued when it ran inline")
    }

    @Test
    fun `off the loop thread the work is queued for the loop rather than run by the caller`() {
        val loop = FakeLoop()
        val handoff = loop.handoff()
        var onLoop = 0
        var ifStopped = 0

        handoff.runOnLoop(onLoop = { onLoop++ }, ifStopped = { ifStopped++ })

        assertEquals(0, onLoop, "the caller must not run loop-owned work itself")
        assertEquals(1, loop.queue.size)

        loop.drain()
        assertEquals(1, onLoop)
        assertEquals(0, ifStopped)
    }

    @Test
    fun `a caller that arrives after the loop is quiet runs the fallback itself`() {
        val loop = FakeLoop()
        val handoff = loop.handoff()
        handoff.markFinished()
        handoff.markQuiescent()
        var onLoop = 0
        var ifStopped = 0

        handoff.runOnLoop(onLoop = { onLoop++ }, ifStopped = { ifStopped++ })

        assertEquals(1, ifStopped, "the loop is gone, so the caller must do the fallback")
        assertEquals(0, onLoop)

        // Nothing was queued: a quiescent loop is not dispatched to at all —
        // the offer would pin the closure in a queue nothing reads. A drain
        // (there will not be one) finds nothing to run.
        assertTrue(loop.queue.isEmpty(), "no task may be offered to a quiescent loop")
        loop.drain()
        assertEquals(0, onLoop, "nothing was queued, so nothing can run")
    }

    @Test
    fun `a caller waits out the final drain before taking the fallback`() = runBlocking {
        // The state the two flags exist to distinguish: the loop has stopped
        // polling (finished) but has not finished draining (not yet quiescent).
        // A caller must wait here — acting on `finished` alone would release an
        // fd the loop can still arm from a queued registration.
        //
        // Collapsing the flags into one would let this caller run its fallback
        // before the drain, which is what the assertions below rule out.
        val loop = FakeLoop()
        val handoff = loop.handoff()
        val drained = AtomicInt(0)
        val ranBeforeDrain = AtomicInt(0)

        handoff.markFinished()

        withTimeout(WAIT_BUDGET) {
            val waiter = launch(Dispatchers.Default) {
                handoff.runOnLoop(
                    onLoop = {},
                    ifStopped = { if (drained.value == 0) ranBeforeDrain.value = 1 },
                )
            }
            // Let the waiter reach the spin, then complete the drain the way the
            // loop does. Publishing quiescence is what releases it.
            launch(Dispatchers.Default) {
                while (loop.queue.isEmpty()) { /* wait for the offer */ }
                drained.value = 1
                handoff.markQuiescent()
            }
            waiter.join()
        }

        assertEquals(0, ranBeforeDrain.value, "the fallback must not run before the drain completes")
    }

    private companion object {
        /**
         * Wall-clock bound for the one test that blocks. The spin it waits on is
         * released by another thread within microseconds; this only exists so a
         * regression that never publishes quiescence fails instead of hanging.
         */
        val WAIT_BUDGET = 15.seconds
    }
}
