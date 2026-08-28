@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a refused join answers, and what it leaves behind.
 *
 * The two reasons are not interchangeable. A swept loop will serve nothing
 * afterwards, so an accept loop that meets one should end; a refused arm leaves
 * the loop running and everyone else served, so ending the accept loop over it
 * would take a healthy server off the air for one connection's failure. The
 * loop is what tells them apart — a construction site reading the loop's state
 * afterwards would be guessing, since the finishing flag is published before
 * the sweep.
 *
 * The rollback is here for the same reason the answer is: it has to give back
 * what this join added and nothing it found already there.
 */
internal class JoinRefusalTest : AbstractReadinessEventLoopFixture() {

    @Test
    fun `a join answers which way it was refused`() {
        val swept = owned(FakeLoop())
        val sweptListener = RecordingListener()
        swept.failRemainingWaiters()

        assertEquals(
            JoinRefusal.LOOP_STOPPED,
            swept.joinLoop(sweptListener, FD, Interest.READ, sweptListener),
            "a loop whose ledgers are closed refused before it registered anything",
        )

        val running = owned(FakeLoop())
        val listener = RecordingListener()
        running.failArmCallback = true

        assertEquals(
            JoinRefusal.ARM_REFUSED,
            running.joinLoop(listener, FD, Interest.READ, listener),
            "a running loop took the registration and the kernel refused the arm",
        )
    }

    @Test
    fun `a join that takes answers with nothing to report`() {
        val loop = owned(FakeLoop())
        val listener = RecordingListener()

        assertNull(loop.joinLoop(listener, FD, Interest.READ, listener), "nothing refused it")
        assertEquals(1, loop.participantCount(), "and it is in the registry it will be told from")
    }

    @Test
    fun `a refused join gives back only the registration it added`() {
        // The registry is a set with no count, so an unconditional rollback
        // would take away a participant that was already in it for something
        // else -- leaving a live registration that is never told the loop
        // stopped, which is the silent connection this path exists to prevent.
        val loop = owned(FakeLoop())
        val listener = RecordingListener()
        loop.addParticipant(listener)
        loop.failArmCallback = true

        assertEquals(JoinRefusal.ARM_REFUSED, loop.joinLoop(listener, FD, Interest.WRITE, listener))

        assertEquals(
            1,
            loop.participantCount(),
            "the standing registration is not this join's to give back",
        )
    }

    @Test
    fun `a refused join gives back the registration it did add`() {
        // The other half, or the test above passes for a rollback that never
        // runs at all.
        val loop = owned(FakeLoop())
        val listener = RecordingListener()
        loop.failArmCallback = true

        assertEquals(JoinRefusal.ARM_REFUSED, loop.joinLoop(listener, FD, Interest.READ, listener))

        assertEquals(
            0,
            loop.participantCount(),
            "a participant this join put there is not left in a registry it will be told from",
        )
    }

    @Test
    fun `a queued arm refused gives back only what its join added`() {
        // The off-loop half of the same rule. The answer cannot come back out
        // of `joinLoop` here -- the arm is still queued when it returns -- so
        // the release happens where the failure does, and has the same reach.
        val loop = owned(FakeLoop(onLoopThread = false, runDispatchedInline = false))
        val standing = RecordingListener()
        loop.addParticipant(standing)
        assertNull(
            loop.joinLoop(standing, FD, Interest.WRITE, standing),
            "off the loop the arm is queued, so the join comes back reported as taken",
        )
        loop.failArmCallback = true

        loop.drainDispatched()

        assertEquals(
            1,
            loop.participantCount(),
            "the registration it held before this join is not this join's to give back",
        )
    }

    @Test
    fun `a queued arm refused gives back the registration it did add`() {
        // The other half, or the test above passes for a release that never
        // runs at all.
        val loop = owned(FakeLoop(onLoopThread = false, runDispatchedInline = false))
        val listener = RecordingListener()
        assertNull(loop.joinLoop(listener, FD, Interest.READ, listener))
        loop.failArmCallback = true

        loop.drainDispatched()

        assertEquals(
            0,
            loop.participantCount(),
            "a participant this join put there is not left in a registry it will be told from",
        )
    }

    @Test
    fun `a refused arm does not end the accept loop and a swept loop does`() {
        // AcceptLoop rethrows CancellationException and ends; anything else it
        // logs before backing off and retrying. So the type is the decision,
        // and these two must not share one.
        assertIs<CancellationException>(
            acceptJoinFailure(JoinRefusal.LOOP_STOPPED),
            "a loop that has stopped will serve nothing this accept loop takes afterwards",
        )
        val refused = acceptJoinFailure(JoinRefusal.ARM_REFUSED)
        assertIs<IllegalStateException>(
            refused,
            "a running loop keeps serving everyone else, so one connection's failure is not the server's",
        )
        assertTrue(
            refused !is CancellationException,
            "and must not be a cancellation by another name, which AcceptLoop would still rethrow: $refused",
        )
    }

    @Test
    fun `an absent reason is answered rather than thrown on`() {
        // Not reachable from a site that checks the join first, but a
        // diagnostic is the wrong place to add a way to fail. It falls to the
        // side that costs one connection rather than the server: reaching it
        // would mean the loop's own bookkeeping was wrong, and that is no
        // reason to stop accepting.
        val absent = acceptJoinFailure(null)
        assertIs<IllegalStateException>(absent)
        assertTrue(absent !is CancellationException, "the fallback must not end the accept loop: $absent")
        assertTrue(joinRefusalReason(null).isNotEmpty())
    }

    @Test
    fun `each reason reads as itself`() {
        // The caller sees this string and nothing else; two causes that read
        // the same would make the answer useless to whoever is holding it.
        assertNotEquals(
            joinRefusalReason(JoinRefusal.LOOP_STOPPED),
            joinRefusalReason(JoinRefusal.ARM_REFUSED),
            "the two causes must not arrive at the caller wearing the same words",
        )
        assertTrue(
            "stopped" in joinRefusalReason(JoinRefusal.LOOP_STOPPED),
            "got: ${joinRefusalReason(JoinRefusal.LOOP_STOPPED)}",
        )
        assertTrue(
            "arm" in joinRefusalReason(JoinRefusal.ARM_REFUSED),
            "got: ${joinRefusalReason(JoinRefusal.ARM_REFUSED)}",
        )
    }
}
