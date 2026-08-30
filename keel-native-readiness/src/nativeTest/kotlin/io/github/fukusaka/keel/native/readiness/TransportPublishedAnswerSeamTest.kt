@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what may still touch an answer once it has been handed out — nothing.
 *
 * The rule these cases share: the instance handed to a waiter must not be
 * appended to after it is published. Two cases take the wind-down's levers (a
 * throwing inactive report, a throwing close stage), one takes the funnel's
 * double pass, two take the close teardown's waiter stage (a late stage
 * failure over a handed-out rider, and the aggregate that must still reach a
 * closer with nothing else to say), and one pins the premise the rule's
 * safety argument rests on — the funnel's answer rides a later loop task.
 * The funnel's own obligations (who answers, from which entry point) stay in
 * [TransportFlushFunnelSeamTest].
 *
 * Every test here drives loop-dispatched work or parks a real waiter, so each
 * is bounded by [withTimeout] (wall-clock: `runBlocking` builder, per the
 * project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportPublishedAnswerSeamTest : TransportSeamFixture() {

    @Test
    fun `a throwing inactive report is not appended to the answer the waiter already holds`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The retry's drain failure is handed to the parked waiter before
            // the wind-down runs, so a failure *of* the wind-down arrives
            // after the hand-over. Suppressed lists are unsynchronized, so
            // appending to the handed-over instance is a write into a list
            // the waiter may be reading; the wind-down failure is warn-logged
            // where it happens and must go nowhere else. The empty-list
            // asserts below are scenario-specific: a rider the drain attaches
            // *before* the hand-over (a failed FIN report, a failed re-arm)
            // is legitimate, and neither scenario here produces one.
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            transport.onReadClosed = { throw InjectedFault("inactive report refused") }
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val waiter = parkFlushWaiter(transport)
            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")

            // The containment rethrows when the wind-down itself failed; the
            // loop's guard is the production consumer of that throw.
            val escaped = assertFailsWith<InjectedFault> { transport.onReady(Interest.WRITE) }

            val awaited = waiter.await().exceptionOrNull()
            assertIs<InjectedFault>(awaited, "the waiter must see the drain failure")
            assertSame(escaped, awaited, "the rethrow carries the very instance the waiter holds")
            assertEquals(
                emptyList(),
                awaited.suppressedExceptions,
                "the answer keeps the suppressed list it was published with -- empty on this path",
            )
            assertTrue(
                eventLoop.warnings.any { "reporting the failed connection inactive threw as well" in it },
                "the wind-down failure is kept in the log instead: ${eventLoop.warnings}",
            )
            assertIs<InjectedFault>(
                eventLoop.logger.causeOfWarning("reporting the failed connection inactive threw as well"),
                "and the warn carries the failure itself, which is its only record now",
            )

            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a throwing close stage is not appended to the answer the waiter already holds`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other wind-down stage. A second failing buffer stays queued
            // past the drain failure, so releasing the queue inside the
            // wind-down's close throws too — again after the drain failure
            // was handed to the waiter. Two queued buffers drain through
            // writev, which the fake scripts separately from write.
            fake.enqueueWritev(fd, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            val stranded = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)
            transport.write(stranded)

            val waiter = parkFlushWaiter(transport)
            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")

            val escaped = assertFailsWith<InjectedFault> { transport.onReady(Interest.WRITE) }

            val awaited = waiter.await().exceptionOrNull()
            assertIs<InjectedFault>(awaited, "the waiter must see the drain failure")
            assertSame(escaped, awaited, "the rethrow carries the very instance the waiter holds")
            assertEquals(
                emptyList(),
                awaited.suppressedExceptions,
                "the answer keeps the suppressed list it was published with -- empty on this path",
            )
            assertTrue(
                eventLoop.warnings.any { "closing the failed connection threw as well" in it },
                "the wind-down failure is kept in the log instead: ${eventLoop.warnings}",
            )
            assertIs<InjectedFault>(
                eventLoop.logger.causeOfWarning("closing the failed connection threw as well"),
                "and the warn carries the failure itself, which is its only record now",
            )
            assertTrue(inactive, "the inactive report itself still went out")

            failing.releaseUnderlying()
            stranded.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a loop-driven refusal whose wind-down failed passes the funnel twice without repeating it`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The double pass the sticky flag's note describes: the
            // settlement runs the funnel inside the drain's catch, and the
            // rethrow that leaves the drain lands in the outer containment,
            // which runs it again with the same instance. Pinned here
            // because the wind-down failure makes the difference observable:
            // a second pass that repeated the wind-down would call the
            // failed notification again and warn for it twice.
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Failed(EPIPE))
            val transport = transport()
            var notifyCalls = 0
            transport.onReadClosed = {
                notifyCalls++
                throw InjectedFault("inactive report refused")
            }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val waiter = parkFlushWaiter(transport)
            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")

            val escaped = assertFailsWith<RefusedWriteException> { transport.onReady(Interest.WRITE) }

            val awaited = waiter.await().exceptionOrNull()
            assertIs<RefusedWriteException>(awaited, "the waiter must see the refusal")
            assertSame(escaped, awaited, "both passes rethrow the very instance the waiter holds")
            assertEquals(1, notifyCalls, "the second pass must not call the failed notification again")
            assertEquals(
                1,
                eventLoop.warnings.count { "reporting the failed connection inactive threw as well" in it },
                "and must not warn for it again: ${eventLoop.warnings}",
            )

            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a stage failing after the teardown handed out the reason does not append to its graph`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The teardown's own deferred drain meets a rider-carrying
            // refusal, which becomes the recorded reason; the waiter stage
            // hands that same instance out, and a later duty's failure --
            // here the aggregate of a refused resume -- must not be appended
            // into the graph the waiters now hold. Suppressed lists are
            // unsynchronized, and each refused resume is already error-logged
            // where it happens.
            rebuildLoop(onLoopThread = true, runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val answered = parkFlushWaiter(transport)
            val refusing = RefusingDispatcher()
            var refusedOutcome: Result<Unit>? = null
            CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
                refusedOutcome = runCatching { transport.awaitPendingFlush() }
            }
            assertFalse(transport.flush(), "coalescing defers the drain to a tick the close will run instead")

            // The teardown's deferred drain raises the rider out of close();
            // the refused resume's aggregate is logged, not attached.
            // Type and identity pin everything: the assertSame below says the
            // closer's throw IS the graph's rider.
            val closeThrow = assertFailsWith<InjectedFault> { transport.close() }

            val received = answered.await().exceptionOrNull()
            assertIs<RefusedWriteException>(received, "the parked waiter is told the recorded refusal")
            val rider = received.suppressedExceptions.single()
            assertSame(closeThrow, rider, "the closer's throw is the same rider the waiters' graph carries")
            assertEquals(
                emptyList(),
                rider.suppressedExceptions,
                "nothing is appended into the handed-out graph after the hand-over",
            )
            assertEquals(1, refusing.attempts, "the refused waiter's dispatcher was consulted")
            assertNull(refusedOutcome, "nothing can reach the refused waiter")
            assertTrue(
                eventLoop.errors.any { "ending the flush waiter of the closing transport" in it },
                "the refused resume is reported where it happened: ${eventLoop.errors}",
            )
            // The report must carry the throwable -- with the aggregate
            // dropped, this error's cause is the refusal's only record.
            assertNotNull(
                eventLoop.logger.causeOfError("ending the flush waiter of the closing transport"),
                "and the report carries the refusal itself",
            )

            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a close that cannot deliver its answer does not report clean`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The detached stage's other arm: with nothing else carried, the
            // waiter stage's aggregate is what the closer must hear. (With
            // something carried it is dropped, which the sibling above pins
            // -- this case is what keeps that drop from becoming "always
            // drop".)
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            val refusing = RefusingDispatcher()
            var refusedOutcome: Result<Unit>? = null
            CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
                refusedOutcome = runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the close")

            val thrown = runCatching { transport.close() }.exceptionOrNull()

            assertNotNull(thrown, "a close that could not deliver its answer must not report clean")
            assertTrue(
                generateSequence(thrown) { it.cause }.any { it is InjectedFault },
                "and the throw derives from the refused resume, not some other stage: $thrown",
            )
            assertEquals(1, refusing.attempts, "the refused waiter's dispatcher was consulted")
            assertNull(refusedOutcome, "nothing can reach the refused waiter")
            assertTrue(
                eventLoop.errors.any { "ending the flush waiter of the closing transport" in it },
                "and the refusal is still reported where it happened: ${eventLoop.errors}",
            )
            assertNotNull(
                eventLoop.logger.causeOfError("ending the flush waiter of the closing transport"),
                "with the report carrying the refusal itself",
            )
            assertFalse(transport.isOpen)
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `the funnel's answer rides a later loop task instead of the throwing frame`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Pins the deferral the funnel's prose rests on: every attach the
            // transport makes to the instance happens in the current task,
            // and the queued resume runs strictly after them, so this path's
            // publication cannot observe a list mid-append. The discriminator
            // is the window between the throwing flush and the drain of the
            // dispatched task -- an inline resume would complete the waiter
            // at the yield below. The funnel suite's deferred-answer case
            // pins the same deferral from the retry's non-refusal branch;
            // this one takes the refusal branch (the settlement's answer)
            // and the direct-flush frame, where the throw escapes to the
            // caller while the answer still rides -- neither is covered
            // there.
            rebuildLoop(onLoopThread = true, runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val waiter = parkFlushWaiter(transport)
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the drain")

            assertFailsWith<RefusedWriteException> { transport.flush() }

            assertFalse(transport.hasFlushWaiter(), "the answer was taken with the snapshot")
            yield()
            assertFalse(
                waiter.isCompleted,
                "the answer must not arrive from the throwing frame -- it rides a later loop task",
            )

            eventLoop.drainDispatched()
            assertIs<RefusedWriteException>(
                waiter.await().exceptionOrNull(),
                "the dispatched task is what delivers it",
            )

            tracker.assertNoLeaks()
        }
    }
}
