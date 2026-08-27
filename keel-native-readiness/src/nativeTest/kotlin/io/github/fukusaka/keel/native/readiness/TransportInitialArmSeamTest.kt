@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.ConnectionFailureException
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * What a connection learns when the arm it joined the loop with is refused.
 *
 * The eager read arm is the only thing that ever delivers an event for a
 * connection that never enables reads — a write-only client, a one-direction
 * logger — so a withdrawn one leaves it unable to hear its peer, not even the
 * close. `joinLoop` used to report that join as taken: the caller held a
 * connection that was joined, open, and permanently deaf, and only an ERROR
 * in the loop's log said otherwise.
 *
 * The two shapes differ in where the answer can go. An attach on the loop
 * gets it back from `joinLoop`, and the construction sites already drop a
 * connection that did not join. An attach from another thread has queued its
 * arm and returned, so the answer reaches the connection itself, on the loop,
 * and ends it there.
 *
 * Both cases drive loop-dispatched work, so both are bounded by [withTimeout]
 * (wall-clock: `runBlocking` builder, per the project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportInitialArmSeamTest : TransportSeamFixture() {

    @Test
    fun `an attach on the loop hears the refusal and does not report itself joined`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val transport = transport()
            eventLoop.failArmCallback = true

            transport.onChannelAttached()

            assertFalse(
                transport.joinedLoop,
                "a connection whose arm was withdrawn has not joined, whatever the ledger took",
            )
            assertEquals(
                0,
                eventLoop.participantCount(),
                "and is not left in the registry it would be told from",
            )
            // The construction sites raise without saying which of the two
            // ways the join failed, so this warning is the only thing that
            // names it.
            assertTrue(
                eventLoop.warnings.any { "the kernel refused the arm for fd=" in it },
                "the loop names the refusal it answered with, got: ${eventLoop.warnings}",
            )
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `an attach off the loop ends the connection when its queued arm is refused`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The connect paths and the coroutine-mode accept build their
            // channels on the calling thread, so the arm is queued and the
            // caller has returned by the time it runs. The answer goes to the
            // connection instead, from the loop.
            rebuildLoop(onLoopThread = false, runDispatchedInline = false, flushCoalescing = false)
            val transport = transport()
            transport.onChannelAttached()
            assertTrue(transport.joinedLoop, "the join itself took; the arm is still queued")
            eventLoop.failArmCallback = true

            eventLoop.drainDispatched()

            assertFalse(transport.isOpen, "a connection that can never hear again is ended, not left deaf")
            assertEquals(
                0,
                eventLoop.participantCount(),
                "and leaves the registry with the notification",
            )
            // Nobody is left in a frame to be told, so the loop's own report
            // is what a reader of this failure has.
            assertTrue(
                eventLoop.errors.any { "was refused after" in it },
                "the loop reports the withdrawal it made alone, got: ${eventLoop.errors}",
            )
            // Drained after parking: this loop holds its dispatched work
            // until asked, which is what let the queued arm be staged at all,
            // and the wait's own registration rides the same queue.
            val told = parkFlushWaiter(transport)
            eventLoop.drainDispatched()
            val reason = told.await().exceptionOrNull()
            assertIs<ConnectionFailureException>(reason, "a wait arriving later is owed the reason, got: $reason")
            tracker.assertNoLeaks()
        }
    }
}
