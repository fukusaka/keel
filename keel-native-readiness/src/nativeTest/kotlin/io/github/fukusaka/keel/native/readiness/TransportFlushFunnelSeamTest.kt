@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import io.github.fukusaka.keel.testing.buf.PointerlessIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
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
 * Every test here drives loop-dispatched work or parks a real waiter, so each
 * is bounded by [withTimeout] (wall-clock: `runBlocking` builder, per the
 * project's timeout rule).
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
            // Under the pre-fix code the tick's throw escaped into the loop's
            // task guard; the contained version completes normally. Either way
            // the assertions below are what discriminate.
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

    @Test
    fun `a drain failure in the dispatched half-close ends the connection`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            eventLoop.close()
            eventLoop = FakeLoop(onLoopThread = false, runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            // Off-loop shutdownOutput dispatches the half-close; under the
            // coalescing opt-out its flush() drains synchronously, so the
            // refusal surfaces inside loop-driven work with only the task
            // guard above it — the fourth loop-driven entry.
            transport.shutdownOutput()
            runCatching { eventLoop.drainDispatched() }

            assertTrue(inactive, "the half-close's drain failure must report the connection inactive")
            assertFalse(transport.isOpen, "and close it, like every other loop-driven failure")
            // The entry left the queue when its bytes were definitively lost,
            // so the deferred FIN was sendable from the half-close's own
            // finally — pinned so the containment is not mistaken for the
            // thing that sent it.
            assertEquals(1, fake.shutdownCalls, "the deferred FIN still went out")

            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a drain failure in the write-readiness retry answers the waiter and ends the connection`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the retry")
            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")

            // Write readiness delivers the retry; its drain failure goes
            // through the same funnel and the readiness containment.
            transport.onReady(Interest.WRITE)

            assertFalse(transport.hasFlushWaiter(), "the retry's drain failure must answer the parked waiter")
            assertIs<InjectedFault>(waiter.await().exceptionOrNull(), "the waiter must see the drain failure")
            assertTrue(inactive, "a loop-driven drain failure must report the connection inactive")
            assertFalse(transport.isOpen)

            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a drained short-circuit sends the deferred FIN before running the completion callbacks`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            eventLoop.close()
            eventLoop = FakeLoop(runDispatchedInline = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            var completions = 0
            transport.onFlushComplete = {
                completions++
                // The KDoc's stated reason for FIN-first: a completion
                // callback may close the transport, after which the FIN is
                // deliberately not sent — so it must already be out.
                transport.close()
            }

            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            transport.shutdownOutput()

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }

            assertEquals(1, fake.shutdownCalls, "the FIN must be sent before the callbacks can close the transport")
            assertEquals(1, completions, "the short-circuit owes the completion callback too")
            assertTrue(waiter.await().isSuccess, "the drained wait completes normally")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a drain failure during close still answers the waiter with the close's cancellation`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            eventLoop.close()
            eventLoop = FakeLoop(runDispatchedInline = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertFalse(transport.flush(), "coalescing defers the drain")

            // close() runs the deferred drain as its first stage; the refusal
            // is carried by the stages and rethrown at the end. The waiter's
            // answer must stay the close path's promise — a cancellation, not
            // the drain's own failure: the wait ends because of the close.
            assertFailsWith<InjectedFault> { transport.close() }
            val outcome = waiter.await().exceptionOrNull()
            assertIs<CancellationException>(
                outcome,
                "a close-time drain failure must not replace the close's cancellation, got: $outcome",
            )
            assertTrue(
                outcome.message.orEmpty().startsWith("transport closed"),
                "the cause must name the transport close, not a stopped loop: ${outcome.message}",
            )

            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a cancelled waiter leaves no stored continuation behind`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked")

            // External cancellation must clear the slot through the parked
            // waiter's cancel hook — the one registration the drained-inline
            // path skips — or every later drain answers a dead continuation
            // while a live probe reads a waiter that is not there.
            waiter.cancel()
            assertFalse(transport.hasFlushWaiter(), "the cancel hook must clear the stored continuation")

            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a tick consumed by the awaited short-circuit does not report the flush again`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            eventLoop.close()
            eventLoop = FakeLoop(runDispatchedInline = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            var completions = 0
            transport.onFlushComplete = { completions++ }

            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "coalescing defers the drain to the tick")

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(waiter.await().isSuccess, "the short-circuit drained the wait inline")
            assertEquals(1, completions, "the drained flush reports once")

            // The consumed tick still sits in the dispatch queue. Running it
            // must not drain again or report a second completion for the same
            // flush.
            eventLoop.drainDispatched()
            assertEquals(1, completions, "a consumed tick must not report the same flush again")

            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a waiter arriving after a contained drain failure is not parked on a dead queue`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The pipeline's flush route contains a drain throw before any
            // waiter exists: simulated by a bare flush whose failure the
            // caller swallows, leaving the queue populated with no tick
            // scheduled and no WRITE armed.
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            transport.write(PointerlessIoBuf(tracker.allocate(16).apply { writerIndex = 5 }))
            runCatching { transport.flush() }

            // Parking would wait for an event that cannot come. The register
            // retries the drain with the waiter stored, so the repeat failure
            // reaches this caller through the funnel and the containment ends
            // the connection.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertIs<ClassCastException>(
                waiter.await().exceptionOrNull(),
                "the waiter must not be parked on a queue nothing will drain",
            )
            assertTrue(inactive, "the retried drain's failure must report the connection inactive")
            assertFalse(transport.isOpen)
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a poisoned mark does not outlive the entries whose drain failed`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)
            // This drain empties the queue (the entry completed) and throws
            // (its release refused): nothing poisoned remains queued.
            assertFailsWith<InjectedFault> { transport.flush() }

            // A later write awaited before the producer's flush is the
            // legitimate park the poisoned-queue retry must leave parked:
            // nothing about this entry has failed.
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(
                transport.hasFlushWaiter(),
                "the waiter parks; the mark must not outlive the entries whose drain failed",
            )
            assertEquals(1, fake.writeCalls, "no eager drain of a write the producer has not flushed")

            waiter.cancel()
            failing.releaseUnderlying()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `the deferred answer reaches the waiter only after the failing task finishes`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            eventLoop.close()
            eventLoop = FakeLoop(runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")

            // The retry's drain failure answers the waiter through the funnel —
            // but the answer is a queued loop task, so it must not outrun the
            // failing task's own wind-down (which may still be attaching
            // suppressed failures to the instance the waiter will receive).
            transport.onReady(Interest.WRITE)
            assertFalse(transport.hasFlushWaiter(), "the answer is taken inline")
            yield()
            assertFalse(waiter.isCompleted, "but delivered only after the failing task finishes")

            eventLoop.drainDispatched()
            assertIs<InjectedFault>(waiter.await().exceptionOrNull(), "the deferred answer carries the failure")

            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused resume of the drained waiter does not end the healthy connection`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            var completions = 0
            transport.onFlushComplete = { completions++ }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            // A scope of its own, not this test's: the waiter's dispatcher
            // refuses the resumption, so that coroutine can never complete.
            val refusing = RefusingDispatcher()
            var outcome: Result<Unit>? = null
            CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
                outcome = runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the retry")
            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")

            // Write readiness delivers the retry and the drain SUCCEEDS; only
            // the waiter's dispatcher refuses the news. The drain frame's
            // containment ends connections over drain failures -- a refusal
            // to hear of a success is not one, and must not reach it.
            transport.onReady(Interest.WRITE)

            assertEquals(1, refusing.attempts, "the seam must have reached the resume")
            assertFalse(transport.hasFlushWaiter(), "the answer was taken even though its delivery was refused")
            assertTrue(transport.isOpen, "a refused notification must not end a healthy connection")
            assertEquals(1, completions, "onFlushComplete must still run after the refused resume")
            assertNull(outcome, "nothing can reach the refused waiter")
            assertTrue(
                eventLoop.errors.any { it.contains("resuming the drained flush waiter for") },
                "the refusal must be reported as the loop's own, got: ${eventLoop.errors}",
            )
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `the stop sweep's refused cancel is reported and the read side is still told`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            var readClosed = false
            transport.onReadClosed = { readClosed = true }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val refusing = RefusingDispatcher()
            CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the sweep")

            // The loop stops under a parked waiter whose dispatcher refuses
            // the cancellation's resume-back. What arrives here is that
            // refusal -- a cancellation handler that throws is taken by the
            // coroutine machinery before this frame -- and it must not take
            // the read-side notification with it.
            transport.onLoopStopped()

            assertEquals(1, refusing.attempts, "the seam must have reached the cancel's resumption")
            assertFalse(transport.hasFlushWaiter(), "the sweep must take the answer even when refused")
            assertTrue(readClosed, "the read-side notification must survive the refusal")
            assertTrue(
                eventLoop.errors.any { it.contains("cancelling the flush waiter for") },
                "the refusal must be reported as the loop's own, got: ${eventLoop.errors}",
            )
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused delivery of the drain failure is reported rather than lost in the deferred task`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val refusing = RefusingDispatcher()
            CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the flush")

            // The drain fails; the funnel defers the waiter's answer to a loop
            // task, and that task's resume meets the refusing dispatcher.
            assertFailsWith<InjectedFault> { transport.flush() }

            assertEquals(1, refusing.attempts, "the deferred task must have reached the resume")
            assertFalse(transport.hasFlushWaiter(), "the failure must take the answer even when refused")
            assertTrue(
                eventLoop.errors.any { it.contains("resuming the flush waiter with the drain failure for") },
                "the refusal must be reported as the loop's own, got: ${eventLoop.errors}",
            )
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
