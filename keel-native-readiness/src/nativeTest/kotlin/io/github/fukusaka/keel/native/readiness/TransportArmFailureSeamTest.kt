@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.ConnectionFailureException
import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.native.posix.ReadResult
import io.github.fukusaka.keel.native.posix.WriteResult
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pins what a failed readiness arm costs, and who hears about it.
 *
 * When `epoll_ctl` / `kevent(EV_ADD)` fails, the arm is withdrawn — and with
 * it the only future event that could drive the queue again. The bytes can
 * never reach the peer, which is the definition of a refused send: the loop
 * hands the failure back up the arm chain, the transport raises it as
 * [RefusedWriteException], and the whole refused-send pipeline runs —
 * waiters answered with the reason, the connection ended, no FIN over the
 * truncated stream. Before this, the withdrawal was an ERROR log the caller
 * never saw: a parked waiter hung until `close()`, and with nobody waiting
 * the bytes were stranded for the connection's idle life. The suspend arm's
 * sibling (`submitArm` → `failUnarmedWaiter`) has answered its waiter this
 * way all along; these cases bring the callback arm to parity.
 *
 * Every test parks real waiters or drives loop-dispatched work, so every
 * test is bounded by [withTimeout] (wall-clock: `runBlocking` builder, per
 * the project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportArmFailureSeamTest : TransportSeamFixture() {

    @Test
    fun `a waiter parked over a queue whose arm fails is told the send was refused`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            transport.write(tracker.allocate(16).apply { writerIndex = 8 })

            val parked = parkFlushWaiter(transport)
            eventLoop.failArmCallback = true

            assertFailsWith<RefusedWriteException>("the arm failure is the drain's, and the drain raises it") {
                transport.flush()
            }

            val told = parked.await().exceptionOrNull()
            assertIs<Throwable>(told, "the parked waiter must be told, not left for the close: $told")
            assertFalse(transport.isOpen, "bytes with no future leave nothing to send on")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `an arm that fails with nobody waiting still ends the connection`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The stranded half of the same defect: no waiter, so nothing
            // would ever have surfaced it — the queue sat unsendable for the
            // connection's idle life, invisible to everything but an ERROR
            // log. The refused-send pipeline reports the connection inactive
            // like any ending connection.
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            transport.write(tracker.allocate(16).apply { writerIndex = 8 })
            eventLoop.failArmCallback = true

            assertFailsWith<RefusedWriteException> { transport.flush() }

            assertTrue(inactive, "the pipeline hears the end; nothing else would ever say it")
            assertFalse(transport.isOpen, "the stranded bytes end the connection rather than outlive it")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a re-arm that fails after the drain armed is settled like the drain's own refusal`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The one raise performFlush's funnel never sees: the obligation
            // group's re-arm runs after the drain returned, so a refusal
            // there escaped the direct flush() with the waiter still parked
            // and nothing recorded -- the stranding this file exists to end,
            // resurfacing one frame up. Found by independent review of this
            // branch's second shape. The drain's own arm succeeds and only
            // the repeat fails, which is what keeps this refusal out of the
            // drain's catch and in the group's hands.
            fake.enqueueWrite(fd, WriteResult.WouldBlock)
            val transport = transport()
            var inactive = false
            transport.onReadClosed = { inactive = true }
            transport.write(tracker.allocate(16).apply { writerIndex = 8 })
            val parked = parkFlushWaiter(transport)
            eventLoop.onArmCallback = { eventLoop.failArmCallback = true }

            assertFailsWith<RefusedWriteException>("the group raises like every funnel exit") {
                transport.flush()
            }

            val told = parked.await().exceptionOrNull()
            assertIs<Throwable>(told, "the parked waiter must be told, not left for the close: $told")
            assertTrue(inactive, "the pipeline hears the end; nothing else would ever say it")
            assertFalse(transport.isOpen, "bytes with no future leave nothing to send on")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `an arm failing in the read re-enable ends the connection instead of escaping the setter`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The one armRead caller outside a read frame: the accept hand-off
            // and Channel-mode read re-enables reach the arm through the
            // readEnabled setter. Unguarded, the raise escaped into whatever
            // frame flipped the flag — measured, a multi-loop accept hand-off
            // left the connection joined, open and deaf for good — so the
            // setter carries its own containment, and the outcome is the same
            // as every other frame's: the connection ends with the reason.
            val transport = transport()
            eventLoop.failArmCallback = true

            transport.readEnabled = true

            assertFalse(transport.isOpen, "the setter's containment ends the connection with the reason")
            val told = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
            assertIs<ConnectionFailureException>(told, "a wait arriving later is owed the reason, got: $told")
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a read re-arm that fails ends the connection instead of leaving it deaf`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The read twin: a connection whose READ arm is withdrawn hears
            // nothing ever again — no bytes, no EOF, no error. The loop-driven
            // read path already contains its failures by ending the connection,
            // and the raised arm failure rides that same containment; the
            // containment holds it, so onReady returns and the end is what
            // shows.
            // One read per event: the loop delivers the bytes and re-arms
            // for the next event, so the failing arm follows this single read.
            fake.enqueueRead(fd, ReadResult.Bytes(4))
            val transport = transport()
            transport.onRead = { it.release() }
            transport.readEnabled = true
            eventLoop.failArmCallback = true

            transport.onReady(Interest.READ)

            assertFalse(transport.isOpen, "a connection that can never hear again is ended, not left deaf")
            val told = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
            assertIs<ConnectionFailureException>(told, "a wait arriving later is owed the reason, got: $told")
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }
}
