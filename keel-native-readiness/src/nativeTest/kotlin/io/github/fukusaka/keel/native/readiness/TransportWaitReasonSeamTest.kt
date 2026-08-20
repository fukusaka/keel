@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.ConnectionFailureException
import io.github.fukusaka.keel.core.EngineFailureException
import io.github.fukusaka.keel.native.posix.ReadResult
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.ECONNRESET
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a flush wait is told about why it will never be answered.
 *
 * The contract has one line to keep: a caller that closed its own channel
 * gets a cancellation, and a caller that did not gets a failure naming what
 * happened. A refused send has kept that line since the refusal began being
 * recorded; the other ways a connection ends without being asked to had
 * nothing recorded, so a wait was cancelled — telling a caller it had asked
 * for something it never asked for.
 *
 * These stage the ends that are not refusals and read what the wait receives,
 * on both sides of the moment the connection ended: one already parked, one
 * arriving after the wreckage is all there is to read. Which of the two a
 * caller was is not something it chose.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportWaitReasonSeamTest : TransportSeamFixture() {

    @Test
    fun `a wait beginning after a contained failure is told what ended the connection`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // A failure the loop contains ends the connection and leaves the
            // same wreckage a refusal does: closed transport, empty queue,
            // nothing that says why. The wait that arrives afterwards has
            // only what was recorded to go on.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            val transport = transport()
            transport.onReadClosed = { throw InjectedFault("inactive handler refused") }

            val contained = runCatching { transport.onPeerClosed(Interest.READ) }.exceptionOrNull()
            assertIs<InjectedFault>(contained, "the containment re-raises what it could not report: $contained")
            assertFalse(transport.isOpen, "the contained failure ends the connection")

            val told = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()

            assertIs<ConnectionFailureException>(told, "the wait must be told the connection failed, got: $told")
            assertIs<InjectedFault>(told.cause, "and what failed must ride along, got: ${told.cause}")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a wait parked when a contained failure ends the connection is told the same thing`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other side of the same moment. The teardown answers this
            // one, the register answers the case above, and neither caller
            // chose which it was -- so they cannot be told different things.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.onReadClosed = { throw InjectedFault("inactive handler refused") }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            val parked = parkFlushWaiter(transport)
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the connection ends")

            val contained = runCatching { transport.onPeerClosed(Interest.READ) }.exceptionOrNull()
            assertIs<InjectedFault>(contained, "the containment re-raises what it could not report: $contained")

            val told = parked.await().exceptionOrNull()
            assertIs<ConnectionFailureException>(told, "the parked wait must be told the connection failed, got: $told")
            assertIs<InjectedFault>(told.cause, "and what failed must ride along, got: ${told.cause}")
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a wait beginning after a refused read is told what ended the connection`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The ordinary way a connection fails: the peer resets, the read
            // comes back refused, and the transport reports the connection
            // inactive. Nothing throws, so no containment sees it -- and a
            // wait arriving afterwards used to be cancelled, which is what it
            // is told when it closed the connection itself.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueRead(fd, ReadResult.Failed(ECONNRESET))
            val transport = transport()
            transport.onReadClosed = { transport.close() }
            transport.readEnabled = true

            transport.onReady(Interest.READ)
            assertFalse(transport.isOpen, "the refused read ends the connection")

            val told = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()

            assertIs<ConnectionFailureException>(told, "the wait must be told the connection failed, got: $told")
            assertTrue(
                checkNotNull(told.message).contains("read()"),
                "and the message must name what failed, got: ${told.message}",
            )
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a wait parked when a refused read ends the connection is told the same thing`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The parked side of the same moment, answered by the teardown
            // the inactive report runs.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            fake.enqueueRead(fd, ReadResult.Failed(ECONNRESET))
            val transport = transport()
            transport.onReadClosed = { transport.close() }
            transport.readEnabled = true
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            val parked = parkFlushWaiter(transport)
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the read fails")

            transport.onReady(Interest.READ)

            val told = parked.await().exceptionOrNull()
            assertIs<ConnectionFailureException>(told, "the parked wait must be told the connection failed, got: $told")
            assertTrue(
                checkNotNull(told.message).contains("read()"),
                "and told which failure, like the wait that arrived later, got: ${told.message}",
            )
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a wait beginning after the loop ended on its own is told the engine failed`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Nobody asked this loop to stop. Every connection it served is
            // gone, this caller's included, and a cancellation would say the
            // caller had asked to end work it never started ending.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            val transport = transport()
            eventLoop.loopBodyFailure = InjectedFault("the loop body threw")

            val escaped = runCatching { eventLoop.loop() }.exceptionOrNull()
            assertIs<InjectedFault>(escaped, "the loop re-raises what it could not handle: $escaped")
            assertTrue(eventLoop.isStopped(), "and takes itself apart on the way out")

            val told = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()

            assertIs<EngineFailureException>(told, "the wait must be told the engine failed, got: $told")
            assertIs<InjectedFault>(told.cause, "and what the loop threw must ride along, got: ${told.cause}")
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a wait parked when the loop ends on its own is told the same thing`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The stop sweep answers this one, on its way out of the same
            // failure. Both sides of that moment, again -- and the record is
            // written before the sweep runs, which is what lets the sweep
            // read it.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.onChannelAttached()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            val parked = parkFlushWaiter(transport)
            assertTrue(transport.hasFlushWaiter(), "the waiter must be parked before the loop ends")

            eventLoop.loopBodyFailure = InjectedFault("the loop body threw")
            runCatching { eventLoop.loop() }

            val told = parked.await().exceptionOrNull()
            assertIs<EngineFailureException>(told, "the parked wait must be told the engine failed, got: $told")
            assertIs<InjectedFault>(told.cause, "and what the loop threw must ride along, got: ${told.cause}")
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a wait beginning inside the wind-down of a failed loop is told the engine failed`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The third moment: not before the loop ended and not after it is
            // quiet, but during the sweep, where a wait is refused rather than
            // parked because nothing is left to wake it. A wait arriving here
            // reads the record through a different flag than the two above,
            // and the record is published before both.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.onChannelAttached()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "the unwritable socket leaves the queue for a later drain")

            // Told after the transport, so its wait begins once the sweep has
            // already been past the connection it waits on.
            var told: Throwable? = null
            eventLoop.addParticipant(
                object : LoopParticipant {
                    override fun onLoopStopped() {
                        launch(start = CoroutineStart.UNDISPATCHED) {
                            told = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
                        }
                    }
                },
            )

            eventLoop.loopBodyFailure = InjectedFault("the loop body threw")
            runCatching { eventLoop.loop() }

            val outcome = told
            assertIs<EngineFailureException>(outcome, "the wait must be told the engine failed, got: $outcome")
            assertIs<InjectedFault>(outcome.cause, "and what the loop threw must ride along, got: ${outcome.cause}")
            transport.close()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a wait on a loop that was asked to stop is still a cancellation`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other arm of the same decision, and the one the contract
            // reserves cancellation for. Without it, a record that was never
            // cleared -- or a helper that stopped reading it -- would answer
            // every stopped loop as a fault and nothing here would notice.
            rebuildLoop(runDispatchedInline = false, flushCoalescing = false)
            val transport = transport()

            eventLoop.loop()
            assertTrue(eventLoop.isStopped(), "the loop ran to completion and published quiescence")

            val told = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()

            assertIs<CancellationException>(told, "a loop asked to stop cancels its waits, got: $told")
            assertTrue(
                checkNotNull(told.message).contains("EventLoop stopped"),
                "and names the loop rather than the connection, got: ${told.message}",
            )
            transport.close()
            tracker.assertNoLeaks()
        }
    }
}
