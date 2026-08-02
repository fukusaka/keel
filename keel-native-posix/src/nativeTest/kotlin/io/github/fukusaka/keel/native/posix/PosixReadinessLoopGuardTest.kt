package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.logging.LogLevel
import kotlinx.coroutines.Runnable
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the two guards the base keeps on its own queue and loop.
 */
@OptIn(InternalPosixEventLoopApi::class)
internal class PosixReadinessLoopGuardTest : AbstractPosixReadinessEventLoopFixture() {

    // --- the two guards on the base's own queue and loop ---

    @Test
    fun `a task that drains again does not lose the batch it re-entered`() {
        // drainTasks is re-entrant from a task it is running. The outer call
        // already drains until the queue is empty, so the inner one has nothing
        // left to do -- and must not clear the shared batch under the iteration.
        val loop = RealQueueLoop()
        val ran = mutableListOf<String>()
        loop.dispatch(EmptyCoroutineContext, Runnable { ran.add("first") })
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                ran.add("re-enters")
                loop.drain()
            },
        )
        loop.dispatch(EmptyCoroutineContext, Runnable { ran.add("third") })

        loop.drain()

        assertEquals(listOf("first", "re-enters", "third"), ran, "every task runs exactly once")
    }

    @Test
    fun `an off-loop registerCallback goes through the real queue and wakes the loop`() {
        // [FakeLoop] answers every pipeline test, and it replaces both `dispatch`
        // and `drainTasks` with a list -- so none of them reaches MpscQueue,
        // drainQueue's batch loop, or the `if (!inEventLoop() &&
        // !handoff.isQuiescent())` wakeup branch an off-loop registration on a
        // live loop actually takes. A regression that queued the arm and
        // skipped the wakeup would leave the re-arm waiting for an unrelated
        // event and pass every one of them.
        val loop = RealQueueLoop(onLoopThread = false)
        loop.registerCallback(FD, Interest.READ, RecordingListener())

        assertTrue(loop.armedCallbacks.isEmpty(), "the arm is queued, not run on the caller")
        assertEquals(1, loop.wakeups, "and an off-loop caller wakes the loop")

        loop.onLoopThread = true
        loop.drain()

        assertEquals(listOf(FD to Interest.READ), loop.armedCallbacks, "the real drain delivers it")
    }

    @Test
    fun `a task that throws does not stop the rest of its batch`() {
        val loop = RealQueueLoop()
        var laterRan = false
        loop.dispatch(EmptyCoroutineContext, Runnable { throw IllegalStateException("boom") })
        loop.dispatch(EmptyCoroutineContext, Runnable { laterRan = true })

        loop.drain()

        assertTrue(laterRan, "the task queued after the throwing one must still run")
        assertTrue(loop.logged.any { it.first == LogLevel.WARN }, "the throw must be reported: ${loop.logged}")
    }

    @Test
    fun `entering the loop a second time is refused without throwing`() {
        // loop() runs as a pthread entry point with nothing above it to catch,
        // so a second entry is reported and ignored rather than thrown -- and it
        // must not re-point the thread identity the whole class reads.
        val loop = RealQueueLoop()
        loop.loop()
        val errorsAfterFirst = loop.logged.count { it.first == LogLevel.ERROR }

        loop.loop()

        assertEquals(0, errorsAfterFirst, "the first entry is not an error")
        assertTrue(
            loop.logged.any { it.first == LogLevel.ERROR && it.second.contains("entered twice") },
            "the second entry must be reported: ${loop.logged}",
        )
        // The log line alone would pass for a guard that reports and then
        // falls through, re-publishing the thread and running the whole
        // teardown a second time on a live loop. The drain count is what
        // says the second entry returned: one completed loop() drains
        // twice -- the final drain, then the sweep's unconditional one --
        // so a fallen-through second entry would double it to four.
        assertEquals(2, loop.drainCalls, "teardown ran once, not twice")
        assertFalse(loop.lockBroken(), "and left the registration lock working")
    }

    @Test
    fun `dispatch wakes the loop only when the caller is off it`() {
        // The branch is one line and its failure mode is a stall, not a log:
        // a cross-thread dispatch that skips the wakeup leaves the task queued
        // while the kernel wait sits on its deadline, or forever. Neither of
        // this file's other subclasses can reach it -- one answers on-loop
        // unconditionally, the other overrides dispatch entirely.
        val loop = RealQueueLoop(onLoopThread = true)
        loop.dispatch(EmptyCoroutineContext, Runnable { })
        assertEquals(0, loop.wakeups, "an on-loop caller drains before the next wait")

        loop.onLoopThread = false
        loop.dispatch(EmptyCoroutineContext, Runnable { })
        assertEquals(1, loop.wakeups, "an off-loop caller has to interrupt the wait")
    }

    @Test
    fun `dispatch to a quiescent loop keeps the offer but skips the wakeup`() {
        // Once the loop published quiescence its close may already have
        // released the wakeup fd -- and the kernel may have re-handed the
        // number -- so the write would land in someone else's descriptor. The
        // offer stays: bounded retention on a queue nothing reads, which is
        // the best a dispatch to a dead loop can do.
        val loop = RealQueueLoop()
        loop.loop() // runs to completion: finished, swept, quiescent
        loop.onLoopThread = false
        var ran = false
        loop.dispatch(EmptyCoroutineContext, Runnable { ran = true })
        assertEquals(0, loop.wakeups, "a quiescent loop must not be woken")
        assertFalse(ran, "and nothing runs the task -- the queue is dead")
    }
}
