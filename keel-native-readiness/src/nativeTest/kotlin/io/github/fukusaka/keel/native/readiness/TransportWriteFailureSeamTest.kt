@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.IOV_MAX
import io.github.fukusaka.keel.native.posix.NativeSocket
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.ECONNRESET
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins what the write path does when the kernel refuses.
 *
 * Two rules, both about not claiming more than happened. **The batch stays
 * within what the syscall accepts**: a gather offering more regions than
 * `IOV_MAX` is not a large write, it is a failure with nothing sent, so the
 * queue is offered in bounded batches instead. **A refused write is not a
 * completed flush**: the bytes are dropped because they can never reach the
 * peer, but the failure is raised rather than answered as success — the
 * funnel then tells the parked waiter and the loop-driven entries end the
 * connection, which is what the read path has always done with its own
 * `Failed`.
 *
 * The waiter and containment tests drive loop-dispatched work, so every test
 * is bounded by [withTimeout] (wall-clock: `runBlocking` builder, per the
 * project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportWriteFailureSeamTest : TransportSeamFixture() {

    @Test
    fun `a gather larger than the platform limit is offered in batches the kernel accepts`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // One region over the limit is the whole defect: the kernel takes
            // none of them and fails, which is indistinguishable from
            // any other argument error once it has happened.
            val counting = WritevCountRecorder(fake)
            val transport = ReadinessIoTransport(fd, eventLoop, tracker, counting)
            repeat(IOV_MAX + 1) { transport.write(tracker.allocate(16).apply { writerIndex = 1 }) }
            fake.enqueueWritev(fd, WriteResult.Written(IOV_MAX), WriteResult.Written(1))

            assertTrue(transport.flush(), "the whole queue drains, in as many calls as that takes")

            assertTrue(
                counting.counts.all { it <= IOV_MAX },
                "no batch may exceed the kernel's limit of $IOV_MAX, got: ${counting.counts}",
            )
            assertEquals(0, transport.pendingByteCount(), "every region was written")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refill from the water-mark callback is left to the next drain rather than pumped inline`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The batch loop exists for the kernel's region limit, not to chase
            // a producer. A resumed producer's write is a new episode and gets
            // a continuation; draining it in the same call would let one
            // connection hold the loop thread for as long as it keeps writing.
            val transport = transport()
            var refilled = false
            transport.onWritabilityChanged = { writable ->
                if (writable && !refilled) {
                    refilled = true
                    transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                }
            }
            val half = HIGH_WATER / 2 + 1
            transport.write(tracker.allocate(half).apply { writerIndex = half })
            transport.write(tracker.allocate(half).apply { writerIndex = half })
            fake.enqueueWritev(fd, WriteResult.Written(half * 2))

            transport.flush()

            assertTrue(refilled, "the drain's ledger update must have resumed the producer")
            assertEquals(1, fake.writevCalls, "the refill must not be drained by the same call")
            assertEquals(5, transport.pendingByteCount(), "the refill stays queued for its own continuation")
            assertTrue(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "and gets one, got: ${eventLoop.armedCallbacks}",
            )
            transport.onWritabilityChanged = null
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused gather discards what the water-mark callback wrote`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Dropping the queue crosses low water, which resumes the producer
            // synchronously -- and a producer that answers by writing leaves
            // the queue non-empty at the raise. Those bytes go with the
            // connection: the send was refused, so there is nothing left to
            // send them on, and arming for them would register interest in a
            // descriptor about to be withdrawn. What they must not do is
            // leak.
            val transport = transport()
            var refilled = false
            transport.onWritabilityChanged = { writable ->
                if (writable && !refilled) {
                    refilled = true
                    transport.write(tracker.allocate(16).apply { writerIndex = 7 })
                }
            }
            val half = HIGH_WATER / 2 + 1
            transport.write(tracker.allocate(half).apply { writerIndex = half })
            transport.write(tracker.allocate(half).apply { writerIndex = half })
            fake.enqueueWritev(fd, WriteResult.Failed(EPIPE))

            assertFailsWith<RefusedWriteException> { transport.flush() }

            assertTrue(refilled, "the drop's ledger update must have resumed the producer")
            assertFalse(transport.isOpen, "a refused send leaves nothing to send on")
            assertEquals(0, transport.pendingByteCount(), "the refill went with the connection")
            fake.assertAllConsumed()

            transport.onWritabilityChanged = null
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused release on the single-write path still arms what the callback wrote`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Same shape one queue entry down, where the write path is the
            // single one rather than the gather: the entry's release refuses,
            // the ledger update that follows resumes the producer, and the
            // frame raises with the producer's bytes queued behind it.
            val transport = transport()
            var refilled = false
            transport.onWritabilityChanged = { writable ->
                if (writable && !refilled) {
                    refilled = true
                    transport.write(tracker.allocate(16).apply { writerIndex = 7 })
                }
            }
            val over = HIGH_WATER + 1
            val failing = FailingReleaseIoBuf(tracker.allocate(over).apply { writerIndex = over })
            transport.write(failing)
            fake.enqueueWrite(fd, WriteResult.Written(over))

            assertFailsWith<InjectedFault> { transport.flush() }

            assertTrue(refilled, "the ledger update must have resumed the producer")
            assertEquals(7, transport.pendingByteCount(), "only the refill remains")
            assertTrue(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "the refill must have a continuation, got: ${eventLoop.armedCallbacks}",
            )
            fake.assertAllConsumed()

            transport.onWritabilityChanged = null
            failing.releaseUnderlying()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a raise with a coalescing tick pending leaves the arm to the tick`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The reporting exit declines to arm against a scheduled tick,
            // because the tick will drain this queue and the arm would buy a
            // redundant syscall plus a wake the tick has already made stale.
            // The raising exit is the same exit for this purpose.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = true)
            val transport = transport()
            var refilled = false
            transport.onWritabilityChanged = { writable ->
                if (writable && !refilled) {
                    refilled = true
                    transport.write(tracker.allocate(16).apply { writerIndex = 7 })
                }
            }
            val half = HIGH_WATER / 2 + 1
            transport.write(tracker.allocate(half).apply { writerIndex = half })
            transport.write(tracker.allocate(half).apply { writerIndex = half })
            assertFalse(transport.flush(), "coalescing defers the drain to a tick")
            assertTrue(eventLoop.armedCallbacks.isEmpty(), "nothing is armed yet, got: ${eventLoop.armedCallbacks}")

            // Write readiness beats the tick: the drain runs with the tick
            // still scheduled, and the kernel refuses it.
            fake.enqueueWritev(fd, WriteResult.Failed(EPIPE))
            transport.onReady(Interest.WRITE)

            assertTrue(refilled, "the drop's ledger update must have resumed the producer")
            assertTrue(
                eventLoop.armedCallbacks.isEmpty(),
                "the pending tick owns the continuation, got: ${eventLoop.armedCallbacks}",
            )
            // The readiness containment ends the connection, whose teardown
            // takes the refill with it -- so the arm this test is about would
            // have been for a transport that is already gone.
            assertFalse(transport.isOpen, "a loop-driven drain failure ends the connection")
            assertEquals(0, transport.pendingByteCount(), "and its teardown releases what was queued")
            fake.assertAllConsumed()

            transport.onWritabilityChanged = null
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a completion callback that writes and then throws still leaves the queue armed`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The report frame is the one place a throw could strand a queue
            // with nothing marking it poisoned: the drain's own failures set
            // that flag, so a later waiter re-drives them, but a failure out
            // here does not. The completion callback runs application code
            // and can both refill the queue and throw.
            val transport = transport()
            fake.enqueueWrite(fd, WriteResult.Written(5))
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            transport.onFlushComplete = {
                transport.onFlushComplete = null
                transport.write(tracker.allocate(16).apply { writerIndex = 9 })
                throw InjectedFault("completion callback refused")
            }

            assertFailsWith<InjectedFault> { transport.flush() }

            assertEquals(9, transport.pendingByteCount(), "the callback's write is still queued")
            assertTrue(
                eventLoop.armedCallbacks.contains(fd to Interest.WRITE),
                "and needs the continuation this frame owed it, got: ${eventLoop.armedCallbacks}",
            )
            fake.assertAllConsumed()

            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a seam reporting more than it was offered cannot drive the ledger negative`() {
        // The walk stops when the queue runs out, so a seam that over-reports
        // leaves bytes it claimed but nothing to attribute them to. Settling
        // by the claim rather than by what left the queue latches isWritable
        // true for the life of the connection -- the ledger gates it and both
        // water marks. The batch total the old shape settled by was
        // self-limiting; a walk that can stop early is not.
        fake.enqueueWritev(fd, WriteResult.Written(12))
        val transport = transport()
        transport.write(tracker.allocate(16).apply { writerIndex = 4 })
        transport.write(tracker.allocate(16).apply { writerIndex = 4 })

        transport.flush()

        assertEquals(0, transport.pendingByteCount(), "the ledger may name only what was queued")
        fake.assertAllConsumed()

        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a refused send ends the connection even when no loop path contained it`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The loop-driven entries wrap the drain and end the connection on
            // a failure. A direct flush -- the shape a coalescing opt-out
            // takes -- had no such wrapper, so the same refusal left the
            // transport open over a write side that can no longer send. Which
            // path ran the drain is not something a caller chooses, so it
            // cannot be what decides whether the connection survives.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            assertFailsWith<RefusedWriteException> { transport.flush() }

            assertFalse(transport.isOpen, "a refused send leaves nothing to send on")
            assertTrue(inactive, "and the connection is reported inactive")
            assertEquals(0, transport.pendingByteCount(), "the discarded bytes leave no ledger behind")
            fake.assertAllConsumed()

            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused send reports the connection inactive once`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Two paths now discover the same dead connection: the funnel
            // ends it, and the loop-driven entry that called the funnel
            // contains the same throw and ends it again. Inactive is a fact
            // about the connection, not an event each discoverer raises.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            var inactiveCalls = 0
            transport.onReadClosed = { inactiveCalls++ }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            transport.onReady(Interest.WRITE)

            assertEquals(1, inactiveCalls, "the connection is reported inactive once")
            assertFalse(transport.isOpen)
            fake.assertAllConsumed()

            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a throwing inactive handler during the closing drain is not the teardown's failure`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The teardown's deferred drain hits a gone peer. Ending the
            // connection a second time from inside that close would run an
            // application callback there, and its throw would ride out on the
            // refusal as though the teardown had left something undone -- the
            // one thing close() is supposed to tell its caller.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.onReadClosed = { throw InjectedFault("inactive handler refused") }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "coalescing defers the drain to the teardown")

            transport.close()

            assertFalse(transport.isOpen)
            assertTrue(
                eventLoop.warnings.any { it.contains("found the peer gone while closing") },
                "the gone peer is reported, got: ${eventLoop.warnings}",
            )
            assertFalse(
                eventLoop.warnings.any { it.contains("did not finish cleaning up") },
                "and not as teardown incompleteness, got: ${eventLoop.warnings}",
            )
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a drain that moved bytes ends the blocked run`() {
        // The counter's whole job is telling ordinary backpressure from a
        // socket that stays unwritable. Two shipped versions of it counted
        // something else -- one never reset, the other let a nested drain
        // answer for its caller -- and neither was caught, because nothing
        // read it.
        // Blocked, moving, blocked. Asserting only after the moving drain
        // cannot tell a reset from no reset -- the max is 1 either way. The
        // second blocked drain is what distinguishes them: 1 if the run was
        // broken, 2 if it was not.
        fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Written(5), WriteResult.WouldBlock)
        val transport = transport()
        transport.write(tracker.allocate(16).apply { writerIndex = 5 })

        assertFalse(transport.flush(), "the socket takes nothing")
        assertEquals(1, transport.longestBlockedDrainRun(), "one drain moved nothing")

        assertTrue(transport.flush(), "the retry writes it")

        transport.write(tracker.allocate(16).apply { writerIndex = 5 })
        assertFalse(transport.flush(), "the socket blocks again")
        assertEquals(
            1,
            transport.longestBlockedDrainRun(),
            "the drain that moved bytes broke the run, so this one starts a new one",
        )

        fake.assertAllConsumed()
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a nested drain does not answer for the drain it runs inside`() {
        // The ledger update resumes a producer synchronously, and a producer
        // that answers by flushing runs a whole drain inside this one. The
        // nested drain finds an empty queue or a blocked socket while the
        // enclosing one is moving real bytes -- so sharing one record between
        // them reads a healthy connection as stuck.
        val half = HIGH_WATER / 2 + 1
        val transport = transport()
        var refilled = false
        transport.onWritabilityChanged = { writable ->
            if (writable && !refilled) {
                refilled = true
                transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                transport.flush()
            }
        }
        fake.enqueueWritev(fd, WriteResult.Written(half * 2))
        fake.enqueueWrite(fd, WriteResult.WouldBlock)
        transport.write(tracker.allocate(half).apply { writerIndex = half })
        transport.write(tracker.allocate(half).apply { writerIndex = half })

        transport.flush()

        assertTrue(refilled, "the ledger update must have resumed the producer")
        assertEquals(
            1,
            transport.longestBlockedDrainRun(),
            "only the nested drain moved nothing; the one it ran inside moved everything",
        )

        transport.onWritabilityChanged = null
        fake.assertAllConsumed()
        transport.close()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a refusal during the closing drain is still the reason a later waiter is told`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The teardown's deferred drain hits a gone peer. The connection
            // is already ending, so nothing re-enters the wind-down -- but
            // the refusal is known, and a caller told only "closed" cannot
            // tell a refused send from an orderly close.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "coalescing defers the drain to the teardown")

            transport.close()
            eventLoop.drainDispatched()

            val ended = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
            assertIs<RefusedWriteException>(
                ended?.cause,
                "the refusal must be the reason given, got: ${ended?.cause}",
            )
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a drain that moved nothing is not credited with a nested drain's bytes`() {
        // The mirror of the nesting case: here the enclosing drain writes
        // nothing -- the send is refused -- and the ledger update that
        // discards its queue resumes a producer whose own drain does write.
        // Folding the nested answer outward would credit the refused drain
        // with bytes it never sent.
        val over = HIGH_WATER + 1
        val transport = transport()
        var refilled = false
        transport.onWritabilityChanged = { writable ->
            if (writable && !refilled) {
                refilled = true
                transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                transport.flush()
            }
        }
        fake.enqueueWritev(fd, WriteResult.Failed(EPIPE))
        fake.enqueueWrite(fd, WriteResult.Written(5))
        transport.write(tracker.allocate(over).apply { writerIndex = over })
        transport.write(tracker.allocate(16).apply { writerIndex = 5 })

        assertFailsWith<RefusedWriteException> { transport.flush() }

        assertTrue(refilled, "the drop's ledger update must have resumed the producer")
        assertEquals(
            1,
            transport.longestBlockedDrainRun(),
            "the refused drain moved nothing, whatever the drain inside it did",
        )

        transport.onWritabilityChanged = null
        eventLoop.drainDispatched()
        tracker.assertNoLeaks()
    }

    @Test
    fun `a stopped loop does not report a connection inactive a second time`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The stop sweep is a readiness path that ends a connection, so it
            // reports through the same place as the rest. Reaching for the
            // callback directly re-reported a connection a peer close had
            // already ended -- visible in Coroutine mode, where the peer-close
            // path deliberately leaves the transport open.
            val transport = transport()
            var inactiveCalls = 0
            transport.onReadClosed = { inactiveCalls++ }

            transport.onPeerClosed(Interest.READ)
            assertEquals(1, inactiveCalls, "the peer close reports it")

            transport.onLoopStopped()
            assertEquals(1, inactiveCalls, "and the stop sweep does not report it again")

            transport.close()
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a close during the batch loop stops it before the next write`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Off-loop close: the flag flips at once but the teardown is
            // dispatched, so the queue is still there when the loop comes
            // round. Nothing may be written for a connection the application
            // has ended.
            rebuildLoop(onLoopThread = false, runDispatchedInline = false, flushCoalescing = false)
            val transport = transport()
            // Enough entries for a second batch, and enough bytes that draining
            // the first crosses back below low water and resumes the producer.
            val each = 64
            repeat(IOV_MAX + 1) { transport.write(tracker.allocate(each).apply { writerIndex = each }) }
            transport.onWritabilityChanged = { writable -> if (writable) transport.close() }
            fake.enqueueWritev(fd, WriteResult.Written(IOV_MAX * each))

            runCatching { transport.flush() }

            assertFalse(transport.isOpen, "the callback closed the connection")
            assertEquals(1, fake.writevCalls, "no write may follow the close")
            transport.onWritabilityChanged = null
            eventLoop.drainDispatched()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a close whose deferred drain is refused does not raise from close`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // close() asks for the connection to be released, and its own KDoc
            // says the unsent data is discarded. A peer that has gone is the
            // ordinary case for that -- the refusal is not a failure of what
            // close() was asked to do, and application cleanup must not have to
            // catch it.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWritev(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 10 })
            transport.write(tracker.allocate(16).apply { writerIndex = 10 })
            assertFalse(transport.flush(), "coalescing defers the drain to a tick that never runs")

            // The teardown performs that deferred drain itself, and the peer
            // refuses it.
            transport.close()

            assertFalse(transport.isOpen)
            assertEquals(0, transport.pendingByteCount(), "the discarded bytes leave no ledger behind")
            // Reported, not silent — the errno itself travels on the
            // throwable, which the recording logger drops by design.
            assertTrue(
                eventLoop.warnings.any { it.contains("found the peer gone while closing") },
                "the refusal is reported, not silent, got: ${eventLoop.warnings}",
            )
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused release during the closing drain still reaches the caller`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // A gone peer is contained because close() was asked to discard
            // those bytes anyway. A release that refuses during the same drain
            // is a different thing -- the teardown did not finish -- and it
            // rides along as a suppressed cause on the very type the
            // containment matches. Containing it because of the company it
            // keeps would make a leak silent exactly when a dead peer
            // coincides with one.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWritev(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 10 })
            transport.write(failing)
            transport.write(tracker.allocate(16).apply { writerIndex = 10 })
            assertFalse(transport.flush(), "coalescing defers the drain to a tick that never runs")

            assertFailsWith<InjectedFault> { transport.close() }

            assertEquals(1, failing.refusedReleases, "the drain must have reached the release")
            assertFalse(transport.isOpen, "the teardown still ends the connection")
            assertTrue(
                eventLoop.warnings.any { it.contains("did not finish cleaning up") },
                "the gone peer is still reported alongside, got: ${eventLoop.warnings}",
            )
            fake.assertAllConsumed()

            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a gather the kernel refused is not a completed flush`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWritev(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 10 })
            transport.write(tracker.allocate(16).apply { writerIndex = 10 })

            // The bytes are unsendable, so dropping them is right -- reporting
            // that the flush completed is not.
            val failure = assertFailsWith<IllegalStateException> { transport.flush() }
            assertTrue(
                checkNotNull(failure.message).contains("writev() failed"),
                "the failure must name the syscall and its errno, got: ${failure.message}",
            )
            assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that are gone")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a write the kernel refused is not a completed flush`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.Failed(ECONNRESET))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val failure = assertFailsWith<IllegalStateException> { transport.flush() }
            assertTrue(
                checkNotNull(failure.message).contains("write() failed"),
                "the failure must name the syscall and its errno, got: ${failure.message}",
            )
            assertEquals(0, transport.pendingByteCount(), "the ledger must not name bytes that are gone")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused write answers the parked waiter with the failure`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val waiter = parkFlushWaiter(transport)
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the flush")

            // The drop empties the queue, so the exit would otherwise report a
            // completion over bytes the peer never saw.
            assertFailsWith<IllegalStateException> { transport.flush() }

            assertFalse(transport.hasFlushWaiter(), "the write failure must answer the parked waiter")
            assertTrue(
                waiter.await().exceptionOrNull() is IllegalStateException,
                "the waiter must see the write failure, got: ${waiter.await()}",
            )
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refused write on the readiness retry ends the connection`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock, WriteResult.Failed(ECONNRESET))
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "the first attempt blocks and arms WRITE")

            // Loop-driven work: the containment ends the connection rather than
            // letting the throw reach the loop's generic task guard -- the same
            // answer the read path gives its own refused syscall.
            transport.onReady(Interest.WRITE)

            assertTrue(inactive, "a refused write must report the connection inactive")
            assertFalse(transport.isOpen, "and close it, like every other loop-driven failure")
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a half-close whose flush was refused tells its caller`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The half-close orders its FIN behind the queued bytes, so a
            // caller on the transport's own context learns the same way a
            // direct flush's caller does when those bytes cannot go. What
            // becomes of the deferred FIN afterwards is a separate question,
            // tracked with the rest of the half-close's contract.
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            val failure = assertFailsWith<IllegalStateException> { transport.shutdownOutput() }
            assertTrue(
                checkNotNull(failure.message).contains("write() failed"),
                "the failure names the syscall and its errno, got: ${failure.message}",
            )

            assertEquals(0, transport.pendingByteCount(), "the unsendable bytes are still dropped")
            fake.assertAllConsumed()
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a reentrant flush during the batch loop does not leave it indexing a drained queue`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The canonical backpressure resume, met by a queue that needs
            // more than one batch: the ledger update between batches resumes
            // a producer, and a producer that answers by flushing drains from
            // the same queue this frame is still counting through.
            val transport = transport()
            val each = 64
            repeat(IOV_MAX + 2) { transport.write(tracker.allocate(each).apply { writerIndex = each }) }
            var reentered = false
            transport.onWritabilityChanged = { writable ->
                if (writable && !reentered) {
                    reentered = true
                    fake.enqueueWritev(fd, WriteResult.Written(each * 2))
                    transport.flush()
                }
            }
            fake.enqueueWritev(fd, WriteResult.Written(IOV_MAX * each))

            assertTrue(transport.flush(), "the queue drains, whoever drained the tail of it")

            assertTrue(reentered, "the ledger update must have resumed the producer")
            assertEquals(0, transport.pendingByteCount(), "every region is out")
            assertEquals(
                2,
                fake.writevCalls,
                "the reentrant drain took the second batch; this frame must not re-offer it",
            )
            fake.assertAllConsumed()
            transport.onWritabilityChanged = null
            transport.close()
            tracker.assertNoLeaks()
        }
    }
}

/**
 * Records the region count of every gather the transport issues, delegating
 * everything else to the [FakeNativeSocket] it wraps.
 *
 * Composition rather than a fake that captures arguments: the fake documents
 * that it records none, so that a test needing one argument does not push
 * capture state onto every test that does not.
 */
@OptIn(ExperimentalForeignApi::class)
private class WritevCountRecorder(private val delegate: FakeNativeSocket) : NativeSocket by delegate {

    val counts: MutableList<Int> = mutableListOf()

    override fun writev(
        fd: Int,
        bases: CPointer<CPointerVar<ByteVar>>,
        lens: CPointer<ULongVar>,
        count: Int,
    ): WriteResult {
        counts += count
        return delegate.writev(fd, bases, lens, count)
    }
}
