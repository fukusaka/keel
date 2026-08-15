package io.github.fukusaka.keel.native.readiness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Chain bookkeeping of [AbstractReadinessEventLoop], driven directly:
 * how waiters are appended, popped and withdrawn, and what the chain looks
 * like from the loop's side.
 *
 * The transitions carry a head-and-tail transfer whose failure mode is silent:
 * a detached tail makes later appends land where nothing will pop them, and
 * the waiter's `accept()` hangs instead of failing.
 *
 * Chains of one, two, three and four nodes are all built. Four is not padding:
 * it is the shortest chain in which a removal target is genuinely interior —
 * neither the head nor the tail — which is the only shape that exercises the
 * walk past a second hop and the tail fixup independently of each other.
 */
@OptIn(InternalReadinessEngineApi::class)
internal class ReadinessLoopLedgerTest : AbstractReadinessEventLoopFixture() {

    @Test
    fun `a single waiter is popped and the key becomes empty`() = loopTest { loop ->
        val w = suspendOn(loop, FD, Interest.READ).await()

        assertEquals(listOf(FD to Interest.READ), loop.armed, "register should arm exactly once")
        assertTrue(loop.waiters(FD, Interest.READ))

        val (popped, more) = loop.popOne(FD, Interest.READ)
        assertSame(w.reg, popped)
        assertFalse(more, "the last waiter leaves the key empty")
        assertNull(loop.popOne(FD, Interest.READ).first, "a second pop finds nothing")
    }

    @Test
    fun `waiters on one key are popped in the order they registered`() = loopTest { loop ->
        val regs = chainOf(loop, 3).map { it.reg }
        assertEquals(regs, loop.drain(FD, Interest.READ))
    }

    @Test
    fun `popping the head of a two-node chain leaves the survivor appendable`() = loopTest { loop ->
        // Two nodes is the case where the tail pointer has to be cleared rather
        // than inherited: the new head IS the tail. Inheriting it leaves a stale
        // tail, and the append below lands behind it, unreachable.
        val (first, second) = chainOf(loop, 2)

        assertSame(first.reg, loop.popOne(FD, Interest.READ).first)
        val third = suspendOn(loop, FD, Interest.READ).await()

        assertEquals(
            listOf(second.reg, third.reg),
            loop.drain(FD, Interest.READ),
            "the late append must still be reachable",
        )
    }

    @Test
    fun `popping the head of a longer chain keeps the tail reachable`() = loopTest { loop ->
        // Three nodes is the case two cannot catch. There the new head really is
        // the tail, so clearing the pointer and inheriting it look the same.
        // With three, the new head must inherit the old tail: losing it makes
        // the next append overwrite the middle node's successor instead of
        // following it, and that waiter is never popped again.
        val chain = chainOf(loop, 3)
        assertSame(chain[0].reg, loop.popOne(FD, Interest.READ).first)
        val late = suspendOn(loop, FD, Interest.READ).await()

        assertEquals(
            listOf(chain[1].reg, chain[2].reg, late.reg),
            loop.drain(FD, Interest.READ),
            "appending after a pop must not detach the waiter that was already last",
        )
    }

    @Test
    fun `unregister removes the head and leaves the rest in order`() = loopTest { loop ->
        val chain = chainOf(loop, 4)
        loop.unregister(chain[0].reg)

        assertFalse(loop.contains(FD, Interest.READ, chain[0].reg))
        val late = suspendOn(loop, FD, Interest.READ).await()
        assertEquals(listOf(chain[1].reg, chain[2].reg, chain[3].reg, late.reg), loop.drain(FD, Interest.READ))
    }

    @Test
    fun `unregister removes an interior waiter and leaves the rest in order`() = loopTest { loop ->
        // Four nodes is the shortest chain with a target that is neither head
        // nor tail, so the walk goes past a second hop and the tail fixup is
        // not involved. Three nodes cannot separate the two.
        val chain = chainOf(loop, 4)
        loop.unregister(chain[2].reg)

        assertFalse(loop.contains(FD, Interest.READ, chain[2].reg))
        val late = suspendOn(loop, FD, Interest.READ).await()
        assertEquals(listOf(chain[0].reg, chain[1].reg, chain[3].reg, late.reg), loop.drain(FD, Interest.READ))
    }

    @Test
    fun `unregister removes the tail and a later append still lands behind it`() = loopTest { loop ->
        // Removing the tail is the case that needs head.tail moved back to the
        // new last node. Leaving it pointing at the removed node makes the next
        // append attach to something already detached, so it is never popped.
        val chain = chainOf(loop, 4)
        loop.unregister(chain[3].reg)

        assertFalse(loop.contains(FD, Interest.READ, chain[3].reg))
        val late = suspendOn(loop, FD, Interest.READ).await()
        assertEquals(listOf(chain[0].reg, chain[1].reg, chain[2].reg, late.reg), loop.drain(FD, Interest.READ))
    }

    @Test
    fun `unregistering the same waiter twice is a no-op`() = loopTest { loop ->
        val only = suspendOn(loop, FD, Interest.READ).await()
        loop.unregister(only.reg)
        loop.unregister(only.reg)
        assertNull(loop.popOne(FD, Interest.READ).first)
    }

    @Test
    fun `unregister uses the interest the waiter registered with`() = loopTest { loop ->
        // unregister is the only member that derives the key from the
        // Registration rather than being handed one. A waiter cancelled on the
        // connect path registers WRITE; deriving READ instead would leave it in
        // the chain forever while appearing to succeed.
        val reader = suspendOn(loop, FD, Interest.READ).await()
        val writer = suspendOn(loop, FD, Interest.WRITE).await()

        loop.unregister(writer.reg)

        assertFalse(loop.waiters(FD, Interest.WRITE), "the WRITE waiter is gone")
        assertTrue(loop.contains(FD, Interest.READ, reader.reg), "the READ waiter is untouched")
    }

    @Test
    fun `read and write on the same fd are separate chains`() = loopTest { loop ->
        val reader = suspendOn(loop, FD, Interest.READ).await()
        val writer = suspendOn(loop, FD, Interest.WRITE).await()

        assertSame(reader.reg, loop.popOne(FD, Interest.READ).first)
        assertTrue(loop.waiters(FD, Interest.WRITE), "popping READ must not touch WRITE")
        assertSame(writer.reg, loop.popOne(FD, Interest.WRITE).first)
    }

    @Test
    fun `cancelAll fails every waiter on the key and empties the chain`() = loopTest { loop ->
        val waiters = chainOf(loop, 3)
        val untouched = suspendOn(loop, FD, Interest.WRITE).await()

        loop.cancelAll(FD, Interest.READ, IllegalStateException("server closed"))

        for (w in waiters) {
            assertFailsWith<IllegalStateException> { w.resumed.await() }
        }
        assertNull(loop.popOne(FD, Interest.READ).first, "the chain is empty afterwards")
        assertTrue(loop.waiters(FD, Interest.WRITE), "the other interest is untouched")
        assertFalse(untouched.resumed.isCompleted)
    }

    @Test
    fun `registerIf appends and arms when wanted`() = loopTest { loop ->
        val accepted = CompletableDeferred<Registration?>()
        launch {
            suspendCancellableCoroutine { cont ->
                accepted.complete(loop.registerIf(FD, Interest.READ, cont) { true })
            }
        }
        val reg = accepted.await()

        assertEquals(listOf(FD to Interest.READ), loop.armed, "an accepted registration must be armed")
        assertSame(reg, loop.popOne(FD, Interest.READ).first)
    }

    @Test
    fun `registerIf declines without appending or arming`() = loopTest { loop ->
        val declined = CompletableDeferred<Registration?>()
        launch {
            suspendCancellableCoroutine { cont ->
                declined.complete(loop.registerIf(FD, Interest.READ, cont) { false })
                cont.resumeWith(Result.success(Unit))
            }
        }

        assertNull(declined.await(), "a declined registration returns null")
        assertFalse(loop.waiters(FD, Interest.READ), "and appends nothing")
        assertTrue(loop.armed.isEmpty(), "and arms nothing")
    }
}
