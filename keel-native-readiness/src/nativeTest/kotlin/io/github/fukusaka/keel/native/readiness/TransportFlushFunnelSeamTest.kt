@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins the flush funnel: whatever entry point runs the drain, a throw out of
 * it answers the caller parked in `awaitPendingFlush` — through `performFlush`
 * itself, so no entry point can forget the obligation — and the loop-driven
 * entries end the connection the same way readiness dispatch does.
 *
 * One failure route the seam cannot reach: a deferred FIN abandoned because
 * the drain failed while the loop was finishing. `isFinishing` answers from
 * the loop's own termination hand-off, which no double can reach — that
 * window belongs to the engines' real-loop stop tests.
 *
 * All three tests park a real waiter, so each is bounded by [withTimeout]
 * (wall-clock: `runBlocking` builder, per the project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportFlushFunnelSeamTest : TransportSeamFixture() {

    @Test
    fun `a drain failure reaches a waiter parked without coalescing`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            // runCatching inside the async: a failed async cancels its parent
            // scope, which would fail the test before its own assertions run.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the flush")

            // The drain throws out of a plain flush() — no tick, no readiness
            // event. The funnel must answer the waiter before the throw
            // reaches the caller, because nothing else ever will: with the
            // opt-out there is no scheduled drain left to complete this wait.
            assertFailsWith<InjectedFault> { transport.flush() }
            assertFalse(transport.hasFlushWaiter(), "the drain failure must answer the parked waiter")
            assertIs<InjectedFault>(waiter.await().exceptionOrNull(), "the waiter must see the drain failure")

            failing.releaseUnderlying()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a drain failure in the coalesced tick answers the waiter and ends the connection`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            eventLoop.close()
            eventLoop = FakeLoop(runDispatchedInline = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the tick")

            assertFalse(transport.flush(), "coalescing defers the drain to the tick")
            // Today the tick's throw escapes into the loop's task guard; the
            // contained version completes normally. Either way the assertions
            // below are what discriminate.
            runCatching { eventLoop.drainDispatched() }

            assertFalse(transport.hasFlushWaiter(), "the tick's drain failure must answer the parked waiter")
            assertIs<InjectedFault>(waiter.await().exceptionOrNull(), "the waiter must see the drain failure")
            assertTrue(inactive, "a loop-driven drain failure must report the connection inactive")
            assertFalse(transport.isOpen, "and close it, like every other loop-driven failure")

            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a drain failure in the awaited short-circuit does not strand the dispatched waiter`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            eventLoop.close()
            eventLoop = FakeLoop(onLoopThread = false, runDispatchedInline = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            // Off-loop caller: the register is dispatched, not run inline.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertFalse(waiter.isCompleted, "the register has not run yet")

            // A flush lands behind the queued register, so when the register
            // runs, the coalesced drain is still scheduled and the register
            // short-circuits it — the throw happens with the continuation not
            // yet resumed, in a context where nothing above re-raises to the
            // caller. A stranded waiter fails this test through its timeout;
            // an answered one completes the await with the drain failure.
            assertFalse(transport.flush(), "coalescing defers the drain behind the queued register")
            runCatching { eventLoop.drainDispatched() }

            assertIs<InjectedFault>(waiter.await().exceptionOrNull(), "the dispatched waiter must not be stranded")

            failing.releaseUnderlying()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    private companion object {
        /** Wall-clock bound for the parked-waiter tests; sibling seam budget. */
        const val FUNNEL_TIMEOUT_MS = 5_000L
    }
}
