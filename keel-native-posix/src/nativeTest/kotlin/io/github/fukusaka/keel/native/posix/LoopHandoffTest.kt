package io.github.fukusaka.keel.native.posix

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
 * No timeout wrapper: nothing here suspends or waits on I/O. The one blocking
 * path — the quiesce spin — is only entered after the fake loop has already
 * been marked quiescent, so it exits on its first read.
 */
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

        // The task was still queued, but claiming it here means a later drain
        // (there will not be one) could not run it a second time.
        loop.drain()
        assertEquals(0, onLoop, "the fallback already claimed the work")
    }

    @Test
    fun `the queued task and the fallback cannot both run`() {
        // Shutdown order as the loop publishes it: finished, drain, quiescent.
        // A caller whose task is already queued and whose drain has run must
        // not also get the fallback — the claim is what rules that out.
        val loop = FakeLoop()
        val handoff = loop.handoff()
        var onLoop = 0
        var ifStopped = 0

        // The loop stops polling, and its final drain runs the queued task.
        handoff.markFinished()
        handoff.markQuiescent()

        // This caller arrives during shutdown: it queues, then finds the loop
        // finished and quiet, so it reaches the claim.
        handoff.runOnLoop(onLoop = { onLoop++ }, ifStopped = { ifStopped++ })
        loop.drain()

        assertEquals(1, onLoop + ifStopped, "exactly one of the two blocks may run")
    }
}
