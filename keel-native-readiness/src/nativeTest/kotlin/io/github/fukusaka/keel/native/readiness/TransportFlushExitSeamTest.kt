@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the funnel exit's episode rule: every path through the shared exit
 * leaves a completion report or a scheduled continuation, and one report
 * covers one episode — a queue that held bytes and ran dry. A mid-drain
 * refill re-arms WRITE, a completed direct flush answers the parked waiter,
 * reentrant flushes from the exit's own callbacks fold into the outer
 * frame's single report, and the continuations the exit leaves behind — a
 * blocked pass's arm, a scheduled tick — report nothing when they land on a
 * queue an earlier flush already drained. The failure funnel and the
 * waiter's answers are pinned by the sibling [TransportFlushFunnelSeamTest].
 *
 * Several tests park a real waiter or drive loop-dispatched work, so every
 * test is bounded by [withTimeout] (wall-clock: `runBlocking` builder, per
 * the project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportFlushExitSeamTest : TransportSeamFixture() {

    @Test
    fun `a queue refilled by the water-mark callback leaves a scheduled continuation behind`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val transport = transport()
            // Cross the high-water mark with one entry so draining it crosses
            // back below low water inside the drain -- the writability
            // callback fires mid-drain and writes again, refilling the queue
            // the drain just emptied.
            val big = tracker.allocate(HIGH_WATER).apply { writerIndex = HIGH_WATER }
            val refill = tracker.allocate(16).apply { writerIndex = 5 }
            var refilled = false
            transport.onWritabilityChanged = { writable ->
                if (writable && !refilled) {
                    refilled = true
                    transport.write(refill)
                }
            }
            transport.write(big)
            fake.enqueueWrite(fd, WriteResult.Written(HIGH_WATER))

            // The drain completes its pass, but the queue it reports on is no
            // longer empty. Completion must not be reported (pinned by the
            // sibling funnel tests) -- and the refill must not be stranded
            // either: nothing but the app's next flush would ever drain it.
            assertFalse(transport.flush(), "a refilled queue is not a completed flush")

            assertTrue(refilled, "the water-mark callback must have written during the drain")
            assertTrue(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "the refill must leave WRITE armed -- a queue with no scheduled continuation waits " +
                    "for an app flush that may never come, got: ${eventLoop.armedCallbacks}",
            )

            // Readiness then drains the refill and completes.
            fake.enqueueWrite(fd, WriteResult.Written(5))
            transport.onReady(Interest.WRITE)
            assertFalse(transport.hasFlushWaiter())
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a direct flush that drains everything answers the parked waiter`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            // A waiter parks first; a producer then flushes directly (the
            // coalescing opt-out path). The drain succeeds -- the waiter must
            // hear about it from this entry too, not stay parked until the
            // next readiness or the close.
            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the flush")

            fake.enqueueWrite(fd, WriteResult.Written(5))
            assertTrue(transport.flush(), "the direct flush drained everything")

            assertFalse(transport.hasFlushWaiter(), "the completed drain must answer the parked waiter")
            yield()
            assertTrue(waiter.isCompleted, "the waiter must have been resumed")
            assertTrue(waiter.await().isSuccess, "the waiter must see the completion, not a failure")
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `one drain reports one completion however the callbacks flush inside it`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val transport = transport()
            var completions = 0
            transport.onFlushComplete = { completions++ }
            val refill = tracker.allocate(16).apply { writerIndex = 5 }
            var reentered = false
            // The canonical backpressure-resume shape: the writability signal
            // resumes a producer that writes and flushes -- synchronously,
            // inside the outer drain's own frame.
            transport.onWritabilityChanged = { writable ->
                if (writable && !reentered) {
                    reentered = true
                    transport.write(refill)
                    transport.flush()
                }
            }
            transport.write(tracker.allocate(HIGH_WATER).apply { writerIndex = HIGH_WATER })
            fake.enqueueWrite(fd, WriteResult.Written(HIGH_WATER), WriteResult.Written(5))

            // The inner flush drains its refill inline and comes straight
            // back; the outer frame owns the report and makes it once.
            assertTrue(transport.flush(), "the episode drained everything")

            assertTrue(reentered, "the callback must have flushed reentrantly")
            assertEquals(1, completions, "one drain episode must report exactly one completion")
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refill that also flushed leaves its tick to drain it instead of racing an arm`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Coalescing on, ticks sequenced by hand: the water-mark callback
            // writes a refill and flushes, which schedules the refill's own
            // tick. Arming WRITE as well would race that tick -- the loser
            // fires on the queue the winner emptied, reporting a completion
            // nothing awaited and draining bytes a producer had not flushed.
            eventLoop.close()
            eventLoop = FakeLoop(runDispatchedInline = false, flushCoalescing = true)
            val transport = transport()
            var completions = 0
            transport.onFlushComplete = { completions++ }
            val refill = tracker.allocate(16).apply { writerIndex = 5 }
            var reentered = false
            transport.onWritabilityChanged = { writable ->
                if (writable && !reentered) {
                    reentered = true
                    transport.write(refill)
                    transport.flush()
                }
            }
            transport.write(tracker.allocate(HIGH_WATER).apply { writerIndex = HIGH_WATER })
            fake.enqueueWrite(fd, WriteResult.Written(HIGH_WATER), WriteResult.Written(5))

            assertFalse(transport.flush(), "the coalesced flush defers to its tick")
            eventLoop.drainDispatched()

            assertTrue(reentered, "the callback must have flushed during the first tick")
            assertEquals(1, completions, "the refill's tick reports its completion; nothing else may")
            assertFalse(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "a tick already scheduled owns the refill -- an arm would race it, got: ${eventLoop.armedCallbacks}",
            )
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `bytes written by the completion callbacks are not stranded behind the report`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val transport = transport()
            var completions = 0
            val late = tracker.allocate(16).apply { writerIndex = 5 }
            var wrote = false
            transport.onFlushComplete = {
                completions++
                if (!wrote) {
                    wrote = true
                    // Writes on hearing of the completion -- and deliberately
                    // does not flush. The report already happened; these bytes
                    // are a new episode and must still get a continuation.
                    transport.write(late)
                }
            }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            fake.enqueueWrite(fd, WriteResult.Written(5), WriteResult.Written(5))

            assertTrue(transport.flush(), "the drain completed and was reported, as of the drain")
            assertTrue(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "the report-side write must leave WRITE armed, got: ${eventLoop.armedCallbacks}",
            )

            // Readiness then drains the new episode and reports it.
            transport.onReady(Interest.WRITE)
            assertEquals(2, completions, "two episodes, two reports")
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a remainder finished by a reentrant flush still answers the waiter`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The outer pass writes partially and blocks; crossing back below
            // low water resumes the producer, whose reentrant flush drains
            // the remainder to completion. The outer frame's own pass did not
            // complete -- but the queue is empty, every byte is out, and the
            // report is owed to whoever is listening: the parked waiter and
            // the completion callback (the deferred FIN is discharged before
            // the report, as the transport's own obligation). The sibling ownership test
            // pins the other halves of this shape (the false return, the
            // absent re-arm); this one pins the report.
            val total = HIGH_WATER + LOW_WATER
            val written = HIGH_WATER + LOW_WATER / 2
            fake.enqueueWrite(fd, WriteResult.Written(written), WriteResult.WouldBlock)
            val transport = transport()
            var completions = 0
            transport.onFlushComplete = { completions++ }
            transport.onWritabilityChanged = { writable ->
                if (writable) {
                    fake.enqueueWrite(fd, WriteResult.Written(LOW_WATER / 2))
                    transport.flush()
                }
            }
            transport.write(tracker.allocate(total).apply { writerIndex = total })

            val waiter = async(start = CoroutineStart.UNDISPATCHED) {
                runCatching { transport.awaitPendingFlush() }
            }
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the flush")

            assertFalse(transport.flush(), "the outer flush still reports its own WouldBlock")

            assertFalse(transport.hasFlushWaiter(), "the emptied queue must answer the waiter, whoever emptied it")
            assertEquals(1, completions, "one emptied queue, one report")
            yield()
            assertTrue(waiter.isCompleted, "the waiter must have been resumed")
            assertTrue(checkNotNull(waiter.await().isSuccess), "the waiter must see the completion")
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a report-side write after a reentrant-completed pass still gets a continuation`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The composition of the two shapes above: the outer pass blocks,
            // a reentrant flush finishes the remainder, the report fires --
            // and a completion callback writes without flushing. The arm must
            // answer to the queue at the exit, not to this frame's own pass:
            // gated on the pass, these bytes had no continuation at all.
            val total = HIGH_WATER + LOW_WATER
            val written = HIGH_WATER + LOW_WATER / 2
            fake.enqueueWrite(fd, WriteResult.Written(written), WriteResult.WouldBlock)
            val transport = transport()
            val late = tracker.allocate(16).apply { writerIndex = 5 }
            var wrote = false
            transport.onFlushComplete = {
                if (!wrote) {
                    wrote = true
                    transport.write(late)
                }
            }
            transport.onWritabilityChanged = { writable ->
                if (writable) {
                    fake.enqueueWrite(fd, WriteResult.Written(LOW_WATER / 2))
                    transport.flush()
                }
            }
            transport.write(tracker.allocate(total).apply { writerIndex = total })

            assertFalse(transport.flush(), "the outer flush still reports its own WouldBlock")

            assertTrue(wrote, "the completion callback must have written during the report")
            assertTrue(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "the report-side write must leave WRITE armed whatever this frame's pass did, " +
                    "got: ${eventLoop.armedCallbacks}",
            )
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `readiness landing on a queue an earlier flush drained does not repeat the report`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The arm the exit leaves for a report-side write goes stale when
            // the application flushes those bytes itself before readiness
            // arrives. The late wake then finds nothing to drain — and a queue
            // that was already empty when the drain was entered is not an
            // episode: its report went out when it emptied. Without the entry
            // check, the wake re-announced that completion.
            fake.enqueueWrite(fd, WriteResult.Written(5), WriteResult.Written(5))
            val transport = transport()
            var completions = 0
            val late = tracker.allocate(16).apply { writerIndex = 5 }
            var wrote = false
            transport.onFlushComplete = {
                completions++
                if (!wrote) {
                    wrote = true
                    transport.write(late)
                }
            }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            assertTrue(transport.flush(), "the first episode drains and is reported")
            assertTrue(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "the report-side write leaves WRITE armed",
            )
            assertTrue(transport.flush(), "the application flushes the second episode itself")
            assertEquals(2, completions, "two episodes, two reports")

            // The armed wake arrives after both episodes ended.
            transport.onReady(Interest.WRITE)
            assertEquals(2, completions, "a stale wake on an emptied queue must not repeat the report")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a blocked pass's arm gone stale by a direct flush does not repeat the report`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The same staleness through the blocked path's own arm: the
            // retry it registered is overtaken by a direct flush that drains
            // everything and reports. The wake that then lands owes nothing.
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            var completions = 0
            transport.onFlushComplete = { completions++ }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")
            assertTrue(transport.flush(), "the direct retry drains everything")
            assertEquals(1, completions, "one episode, one report")

            transport.onReady(Interest.WRITE)
            assertEquals(1, completions, "the overtaken retry must not repeat the report")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a scheduled tick landing on a queue readiness already drained does not repeat the report`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The third stale continuation: under coalescing, a second flush()
            // schedules a tick while the blocked first pass's arm is out, and
            // readiness wins the race. The tick still fires — its schedule flag
            // is only consumed by the awaited short-circuit — and lands on the
            // queue readiness emptied and reported.
            eventLoop.close()
            eventLoop = FakeLoop(runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            var completions = 0
            transport.onFlushComplete = { completions++ }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            assertFalse(transport.flush(), "the first flush defers to its tick")
            eventLoop.drainDispatched()
            assertTrue(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "the tick's blocked pass arms WRITE",
            )
            assertFalse(transport.flush(), "the second flush schedules another tick")

            transport.onReady(Interest.WRITE)
            assertEquals(1, completions, "readiness drained the queue and reported it")

            eventLoop.drainDispatched()
            assertEquals(1, completions, "the overtaken tick must not repeat the report")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a deferred FIN goes out with the direct flush that drains its bytes`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Pins the opt-out half of the FIN deferral: the half-close's own
            // attempt blocks, and the completion path that finally drains the
            // bytes is a plain direct flush() — whose exit owes the FIN like
            // every other emptying entry.
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            assertFalse(transport.flush(), "the first attempt blocks")
            transport.shutdownOutput()
            assertEquals(0, fake.shutdownCalls, "the FIN is deferred behind the buffered bytes")

            assertTrue(transport.flush(), "the direct retry drains everything")
            assertEquals(1, fake.shutdownCalls, "the emptying direct flush must send the deferred FIN")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a deferred FIN survives a drain that threw after emptying the queue`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The entry that empties the queue does not always report: a
            // refused release throws out of the drain after the entry was
            // dequeued, so the frame leaves past the report with the queue
            // empty and the FIN still deferred. The FIN is the transport's
            // own obligation, not the episode's — the next entry to observe
            // the drained queue owes it, report or no report.
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.WouldBlock, WriteResult.Written(5))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")
            transport.shutdownOutput()
            assertEquals(0, fake.shutdownCalls, "the FIN is deferred behind the buffered bytes")

            // The drain sends the bytes, then the refused release throws —
            // queue empty, FIN unsent, connection still open (a direct
            // flush's throw belongs to the pipeline's error path).
            assertFailsWith<InjectedFault> { transport.flush() }
            assertEquals(0, fake.shutdownCalls, "the throw left the report unreached")

            // The armed wake is the FIN's only remaining completion path.
            transport.onReady(Interest.WRITE)
            assertEquals(1, fake.shutdownCalls, "the stale wake must send the deferred FIN")

            failing.releaseUnderlying()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a reentrant flush refilled by its own callbacks does not answer true`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The reentrant branch answers the same own-pass rule as the
            // outer frame: a pass whose ledger update refilled the queue is
            // not a completed flush, however completely it drained what it
            // found. The refill rides the outer frame's exit for its
            // continuation.
            val total = HIGH_WATER + LOW_WATER
            fake.enqueueWrite(fd, WriteResult.Written(5), WriteResult.Written(total))
            val transport = transport()
            var reentrantAnswer: Boolean? = null
            var refilled = false
            transport.onFlushComplete = {
                if (reentrantAnswer == null) {
                    transport.write(tracker.allocate(total).apply { writerIndex = total })
                    reentrantAnswer = transport.flush()
                }
            }
            transport.onWritabilityChanged = { writable ->
                if (writable && !refilled) {
                    refilled = true
                    transport.write(tracker.allocate(16).apply { writerIndex = 10 })
                }
            }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            assertTrue(
                transport.flush(),
                "the outer frame's own pass emptied — the report-side bytes are a new episode",
            )
            assertTrue(refilled, "the reentrant drain's low-water crossing refilled the queue")
            assertEquals(false, reentrantAnswer, "a refilled queue is not a completed flush, reentrant or not")
            assertEquals(10, transport.pendingByteCount(), "the refill is still queued")
            transport.onWritabilityChanged = null
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a consumed tick does not eagerly drain bytes the producer has not flushed`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The tick's consumed-already check is not about the report — the
            // entry predicate silences that on its own — but about the drain
            // itself: a producer that wrote after the awaited short-circuit
            // took the schedule has not asked for a flush yet, and the spent
            // tick draining those bytes would jump its coalescing turn.
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

            // Written after the schedule was consumed, not flushed.
            transport.write(tracker.allocate(16).apply { writerIndex = 7 })

            eventLoop.drainDispatched()
            assertEquals(7, transport.pendingByteCount(), "the consumed tick must leave unflushed bytes queued")
            assertEquals(1, completions, "and must not report anything for them")
            // The spent tick must not even attempt the drain: one write
            // syscall for the short-circuit's own drain, and no WRITE arm
            // for bytes whose flush has not been asked for.
            assertEquals(1, fake.writeCalls + fake.writevCalls, "the spent tick must not attempt a drain")
            assertFalse(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "a drain nobody scheduled must not arm WRITE, got: ${eventLoop.armedCallbacks}",
            )

            transport.close()
            tracker.assertNoLeaks()
        }
    }

    private companion object {
        /** The transport's water marks; the imports tie the tests to the real thresholds. */
        const val HIGH_WATER = IoTransport.DEFAULT_HIGH_WATER_MARK
        const val LOW_WATER = IoTransport.DEFAULT_LOW_WATER_MARK

        /** Wall-clock bound for the parked-waiter tests; sibling seam budget. */
        const val FUNNEL_TIMEOUT_MS = 5_000L
    }
}
