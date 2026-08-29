@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import io.github.fukusaka.keel.testing.buf.PointerlessIoBuf
import io.github.fukusaka.keel.testing.buf.ReleaseHookIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
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
 * Pins the flush funnel and the waiter's answers. The funnel half: whatever
 * entry point runs the drain, a throw out of it answers the caller parked in
 * `awaitPendingFlush` — through `performFlush` itself, so no entry point can
 * forget the obligation — and the loop-driven entries end the connection the
 * same way readiness dispatch does. The answer half: the deliveries this
 * file pins — drained success, deferred failure, loop-stop cancel, and the
 * register's two reachable immediate arms, dispatched so the answer rides
 * the waiter's own dispatcher — have a refusal reported as the transport's
 * own rather than escaping into the frame that delivered; two of those
 * refusal tests involve no drain at all. The exit's episode rule — one
 * report per emptied queue, and the continuations the exit leaves — is
 * pinned by the sibling [TransportFlushExitSeamTest]. Outside the
 * dispatcher contract on purpose:
 * the teardown's two staged cancels (carried to `close()`'s caller), and
 * the answers no dispatcher can refuse because the caller has not suspended
 * — the register's arms run inline on-loop (including the
 * reentrantly-drained re-check, whose window exists only for a register
 * running inside the exit's report, i.e. inline — measured: the resume
 * lands before the suspension completes and consults no dispatcher), and
 * the quiescent-loop cancel that answers before the register is ever
 * dispatched.
 *
 * Two routes the seam cannot reach: a deferred FIN abandoned because the
 * drain failed while the loop was finishing, and the register's
 * finishing-loop arm. Both hang on `isFinishing`, which answers from the
 * loop's own termination hand-off — no double can reach it; those windows
 * belong to the engines' real-loop stop tests.
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
            val waiter = parkFlushWaiter(transport)
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
            rebuildLoop(runDispatchedInline = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val waiter = parkFlushWaiter(transport)
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
            rebuildLoop(onLoopThread = false, runDispatchedInline = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            // Off-loop caller: the register is dispatched, not run inline.
            val waiter = parkFlushWaiter(transport)
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
            rebuildLoop(onLoopThread = false, runDispatchedInline = false, flushCoalescing = false)
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

            val waiter = parkFlushWaiter(transport)
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
            val closeThrow = assertFailsWith<InjectedFault> { transport.close() }
            assertEquals(
                "release refused by FailingReleaseIoBuf",
                closeThrow.message,
                "the rider still reaches the closer",
            )

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
            assertEquals(1, refusing.attempts, "the refused waiter's dispatcher was consulted")
            assertNull(refusedOutcome, "nothing can reach the refused waiter")
            assertTrue(
                eventLoop.errors.any { "ending the flush waiter of the closing transport" in it },
                "and the refusal is still reported where it happened: ${eventLoop.errors}",
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
            // at the yield below.
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

    @Test
    fun `a drained short-circuit sends the deferred FIN before running the completion callbacks`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            rebuildLoop(runDispatchedInline = false)
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

            val waiter = parkFlushWaiter(transport)

            assertEquals(1, fake.shutdownCalls, "the FIN must be sent before the callbacks can close the transport")
            assertEquals(1, completions, "the short-circuit owes the completion callback too")
            assertTrue(waiter.await().isSuccess, "the drained wait completes normally")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a drain failure during close still answers the waiter with the close's cancellation`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            rebuildLoop(runDispatchedInline = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val waiter = parkFlushWaiter(transport)
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
    fun `a cancelled waiter's entry is disposed of harmlessly by the next answer`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            val waiter = parkFlushWaiter(transport)
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked")

            // There is deliberately no cancel hook: the one the old slot
            // installed ran on the cancelling caller's thread — an off-loop
            // write to loop-confined state — and cleared whichever waiter the
            // slot held by then. The entry stays until the next answer, whose
            // resume the coroutine machinery ignores on a cancelled
            // continuation; what this pins is that the disposal is harmless.
            waiter.cancel()
            assertTrue(transport.hasFlushWaiter(), "the entry stays listed until an answer disposes of it")

            fake.enqueueWrite(fd, WriteResult.Written(5))
            assertTrue(transport.flush(), "the retried drain completes past the dead entry")
            assertFalse(transport.hasFlushWaiter(), "and the answer leaves nothing behind")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a tick consumed by the awaited short-circuit does not report the flush again`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            rebuildLoop(runDispatchedInline = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            var completions = 0
            transport.onFlushComplete = { completions++ }

            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "coalescing defers the drain to the tick")

            val waiter = parkFlushWaiter(transport)
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
    fun `a waiter parked inside the exit's report is answered by its own register`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The completion callback runs the ordinary producer sequence
            // synchronously: write, flush (schedules a tick), await. The
            // register consumes that tick and short-circuits the drain — but
            // it is running inside the enclosing exit's report, so the drain
            // is reentrant and reports nothing. The register cannot rely on
            // the report to consume its waiter there: it re-checks the queue
            // it just drained and answers the waiter itself.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Written(5), WriteResult.Written(5))
            val transport = transport()
            val scope = this
            var completions = 0
            var waiter: Deferred<Result<Unit>>? = null
            transport.onFlushComplete = {
                completions++
                if (waiter == null) {
                    transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                    transport.flush()
                    waiter = scope.parkFlushWaiter(transport)
                }
            }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "coalescing defers the first episode to its tick")

            eventLoop.drainDispatched()

            assertFalse(transport.hasFlushWaiter(), "the register must not leave its waiter parked on an emptied queue")
            assertEquals(0, transport.pendingByteCount(), "the short-circuited drain emptied the second episode")
            assertTrue(checkNotNull(waiter).await().isSuccess, "the waiter must see the completion")
            assertEquals(1, completions, "the reentrant episode stays folded — the register answers only its waiter")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a waiter whose emptying drain raced an off-loop close hears the close instead of success`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The release hook plays the racing thread: close() lands off-loop
            // (markClosing flips `opened` synchronously, the teardown is
            // dispatched), then the release refuses -- the drain emptied the
            // queue but threw before any report, and the failure funnel
            // declined its waiter on the flipped flag. The register's
            // re-check is the last to see this waiter; "the flush completed"
            // is not something it can honestly report on a closing
            // connection, so it must answer with the close's own cause --
            // same ranking as its first arm.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val racing = ReleaseHookIoBuf(tracker.allocate(16).apply { writerIndex = 5 }) {
                eventLoop.onLoopThread = false
                transport.close()
                eventLoop.onLoopThread = true
            }
            transport.write(racing)
            assertFalse(transport.flush(), "coalescing defers the drain to the tick")

            // The register runs inline, consumes the tick, and short-circuits
            // the drain; everything above happens inside this call.
            val waiter = parkFlushWaiter(transport)

            assertFalse(transport.hasFlushWaiter(), "the waiter must not be left parked")
            val failure = waiter.await().exceptionOrNull()
            assertIs<CancellationException>(
                failure,
                "a closing connection's waiter hears the close, not success, got: ${waiter.await()}",
            )
            assertTrue(
                checkNotNull(failure.message).contains("transport closed before the pending flush"),
                "the cancellation must carry the close's own cause, got: ${failure.message}",
            )

            // The dispatched teardown finds the slot already answered.
            eventLoop.drainDispatched()
            racing.releaseUnderlying()
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
            val waiter = parkFlushWaiter(transport)
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
            val waiter = parkFlushWaiter(transport)
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
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            val waiter = parkFlushWaiter(transport)
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
                eventLoop.errors.any { it.contains("ending the flush waiter for") },
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

    @Test
    fun `a refused answer at the register's already-drained arm is reported rather than thrown at the loop`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Off-loop caller: the register Runnable is dispatched, so by the
            // time it runs the caller has suspended and the immediate answer
            // rides the waiter's own dispatcher, like any other hand-off.
            rebuildLoop(onLoopThread = false, runDispatchedInline = false, flushCoalescing = false)
            val transport = transport()

            val refusing = RefusingDispatcher()
            CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }

            // Nothing is pending, so the register answers with an immediate
            // resume -- refused. The refusal must be reported as the
            // transport's own, not left for the loop's generic task guard.
            eventLoop.drainDispatched()

            assertEquals(1, refusing.attempts, "the dispatched register must have reached the resume")
            assertTrue(
                eventLoop.errors.any { it.contains("resuming the already-drained flush waiter for") },
                "the refusal must be reported as the transport's own, got: ${eventLoop.errors}",
            )
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused answer at the register's closed-transport arm is reported rather than thrown at the loop`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            rebuildLoop(onLoopThread = false, runDispatchedInline = false, flushCoalescing = false)
            val transport = transport()
            transport.close()

            // A caller that raced the close: its register is dispatched, and by
            // the time it runs the transport is gone -- the cancel it is owed
            // rides its dispatcher, which refuses. The third arm (a finishing
            // loop) has the same guard but no seam to it: `isFinishing` answers
            // from the loop's real termination hand-off, which no double
            // reaches -- that window belongs to the engines' stop tests.
            val refusing = RefusingDispatcher()
            CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            eventLoop.drainDispatched()

            assertEquals(1, refusing.attempts, "the dispatched register must have reached the cancel")
            assertTrue(
                eventLoop.errors.any { it.contains("answering the flush waiter of a closed transport for") },
                "the refusal must be reported as the transport's own, got: ${eventLoop.errors}",
            )
            tracker.assertNoLeaks()
        }
    }
}
