@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.ConnectionFailureException
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
}
