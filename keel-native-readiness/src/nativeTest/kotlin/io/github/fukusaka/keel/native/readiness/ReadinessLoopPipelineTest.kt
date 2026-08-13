package io.github.fukusaka.keel.native.readiness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the pipeline path — the readiness dispatch and callback ledger
 * that moved onto the base class.
 */
@OptIn(InternalReadinessEngineApi::class)
internal class ReadinessLoopPipelineTest : AbstractReadinessEventLoopFixture() {

    // --- the pipeline path, which moved onto the base with this class ---

    @Test
    fun `a callback is registered before it is armed`() = loopTest { loop ->
        // The order is the contract: the arm can report readiness the instant
        // the kernel accepts it, so a listener that is not in the map yet would
        // be a dropped event. The fake records the arm, so seeing the listener
        // already present when it runs is what pins the order.
        var registeredWhenArmed = false
        loop.onArmCallback = { registeredWhenArmed = loop.hasCallbackRegistration(FD, Interest.READ) }

        loop.registerCallback(FD, Interest.READ, RecordingListener())

        assertTrue(registeredWhenArmed, "the listener must be in the map before the arm runs")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `an off-loop registerCallback arms through the funnel`() = loopTestWith(
        FakeLoop(onLoopThread = false),
    ) { loop ->
        // The pipeline twin of the suspend path's funnel test. registerCallback is
        // the member that moved, and its only non-trivial behaviour is the fork in
        // submitOnLoop -- every other test here runs on-loop, so the branch that
        // captures fd/interest/key into a Runnable was never taken.
        val listener = RecordingListener()

        loop.registerCallback(FD, Interest.READ, listener)

        assertEquals(1, loop.dispatchCount, "an off-loop registration goes through dispatch")
        assertEquals(
            listOf(FD to Interest.READ),
            loop.armedCallbacks,
            "through the callback hook, not the suspend one -- epoll maps READ differently on each",
        )
        assertTrue(loop.armed.isEmpty(), "the suspend hook is not the one that fires")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `a second registration on one key replaces the first`() = loopTest { loop ->
        // The contract registerCallback documents, and the one every re-arm
        // depends on. The re-arm tests cannot see it: they re-register the same
        // object, so "registered afterwards" holds whether the ledger replaced,
        // kept or chained. Two distinct listeners is what separates those.
        val replaced = RecordingListener()
        val current = RecordingListener()
        loop.registerCallback(FD, Interest.READ, replaced)
        loop.registerCallback(FD, Interest.READ, current)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(listOf(Interest.READ), current.ready, "the later registration is the one that runs")
        assertEquals(emptyList(), replaced.ready, "and the one it displaced is never called, nor told")
    }

    @Test
    fun `the arm is handed the key of the interest it is arming`() = loopTest { loop ->
        // Both real overrides withdraw `popCallback(key)` when the arm fails,
        // so a key derived from the wrong interest takes the wrong listener out
        // of the ledger. Nothing else here can see that: every
        // other probe goes through dispatchReady, which computes its own key.
        loop.registerCallback(FD, Interest.READ, RecordingListener())
        loop.registerCallback(FD, Interest.WRITE, RecordingListener())

        assertEquals(
            listOf(loop.keyFor(FD, Interest.READ), loop.keyFor(FD, Interest.WRITE)),
            loop.armedCallbackKeys,
            "each arm gets the key for its own interest, in registration order",
        )
    }

    @Test
    fun `a failed arm withdraws the listener for that interest and no other`() = loopTest { loop ->
        // What the recorded key is actually for. Both engines withdraw
        // `popCallback(key)` when the arm fails, so a base handing over a key
        // built from the wrong interest silently removes the wrong listener --
        // and both would still look armed from dispatchReady, which derives its
        // own key.
        loop.registerCallback(FD, Interest.READ, RecordingListener())
        loop.failArmCallback = true

        loop.registerCallback(FD, Interest.WRITE, RecordingListener())

        // Pins the interest the key encodes. Which listener a failed arm
        // withdraws is pinned separately, by the identity test below.
        assertFalse(loop.hasCallbackRegistration(FD, Interest.WRITE), "the failed arm takes its own listener out")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ), "and leaves the other interest alone")

        // What the base then does with a readiness event for the withdrawn
        // interest: nothing is registered, so it warns and takes the kernel
        // interest back rather than re-firing forever.
        loop.dispatchReadyFor(FD, Interest.WRITE, eofFlag = false)

        assertEquals(listOf(FD to Interest.WRITE), loop.disarmed)
        assertTrue(loop.warnings.any { it.contains("no handler") }, "the withdrawal must be visible: ${loop.warnings}")
    }

    @Test
    fun `a queued arm does not fire for a listener that was replaced`() = loopTestWith(
        FakeLoop(onLoopThread = false, runDispatchedInline = false),
    ) { loop ->
        // Why the guard is by identity and not by presence. The ledger holds one
        // entry per key, so a replacement passes a presence test -- and then it
        // is the entry an arm failure withdraws. Weakening the check to
        // hasCallbackListener(key) leaves every other test in this file green.
        val replaced = RecordingListener()
        loop.registerCallback(FD, Interest.READ, replaced)
        loop.unregisterCallback(FD, Interest.READ)
        val current = RecordingListener()
        loop.onLoopThread = true
        loop.registerCallback(FD, Interest.READ, current)
        loop.armedCallbacks.clear()
        loop.onLoopThread = false

        loop.drainDispatched()

        assertTrue(
            loop.armedCallbacks.isEmpty(),
            "the queued arm belonged to a listener that is gone; it must not arm on the replacement's behalf",
        )
    }

    @Test
    fun `a failed arm withdraws its own listener and never a replacement`() = loopTest { loop ->
        // The other half of the identity rule. The pre-arm check keeps a queued
        // arm from firing for a listener that is gone; this keeps a *failing*
        // arm from taking the entry that superseded it. Withdrawing by key alone
        // passes both engines' seam tests -- there is one listener there -- and
        // silently evicts a replacement whose own arm already succeeded, which
        // no error names, because the error names the listener that failed.
        val superseded = RecordingListener()
        val replacement = RecordingListener()
        loop.registerCallback(FD, Interest.READ, superseded)
        loop.registerCallback(FD, Interest.READ, replacement)
        val key = loop.keyFor(FD, Interest.READ)

        assertFalse(
            loop.popIfCurrent(key, superseded),
            "the superseded listener is not on the key, so its failure withdraws nothing",
        )
        assertTrue(
            loop.hasCallbackRegistration(FD, Interest.READ),
            "and the replacement stays registered, armed, and reachable",
        )

        assertTrue(loop.popIfCurrent(key, replacement), "the entry that is there is withdrawable by its owner")
        assertFalse(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `an off-loop registerCallback does not arm until the loop drains it`() = loopTestWith(
        FakeLoop(onLoopThread = false, runDispatchedInline = false),
    ) { loop ->
        // The pipeline twin of the suspend path's deferred-arm test, and the
        // window the callback path's missing stale-registration guard lives in:
        // the listener is in the ledger and the kernel knows nothing yet, so a
        // teardown landing here withdraws a listener whose arm still runs.
        val listener = RecordingListener()

        loop.registerCallback(FD, Interest.READ, listener)

        assertEquals(1, loop.dispatchCount, "the arm is queued, not run")
        assertTrue(loop.armedCallbacks.isEmpty(), "nothing reaches the kernel while it sits there")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ), "but the listener is already in the ledger")

        loop.unregisterCallback(FD, Interest.READ)
        loop.drainDispatched()

        assertTrue(
            loop.armedCallbacks.isEmpty(),
            "a withdrawn listener's queued arm must not reach the kernel -- the fd may be closed by then",
        )
        assertFalse(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `unregisterCallback drops only the matching interest`() = loopTest { loop ->
        loop.registerCallback(FD, Interest.READ, RecordingListener())
        loop.registerCallback(FD, Interest.WRITE, RecordingListener())

        loop.unregisterCallback(FD, Interest.READ)

        assertFalse(loop.hasCallbackRegistration(FD, Interest.READ))
        assertTrue(loop.hasCallbackRegistration(FD, Interest.WRITE), "the other half must survive")
    }

    @Test
    fun `readiness reaches the listener and disarms when it does not re-arm`() = loopTest { loop ->
        val listener = RecordingListener()
        loop.registerCallback(FD, Interest.WRITE, listener)

        loop.dispatchReadyFor(FD, Interest.WRITE, eofFlag = false)

        assertEquals(listOf(Interest.WRITE), listener.ready)
        assertEquals(emptyList(), listener.peerClosed)
        assertEquals(listOf(FD to Interest.WRITE), loop.disarmed, "a callback that does not re-arm is taken back")
    }

    @Test
    fun `a listener that re-arms during onReady keeps its interest`() = loopTest { loop ->
        // What a READ callback does every time, via armRead. Disarming here
        // would discard a live registration.
        val listener = RecordingListener(reArmOn = loop)
        loop.registerCallback(FD, Interest.READ, listener)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(emptyList(), loop.disarmed, "the re-armed interest must not be taken back")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `eof reaches onPeerClosed after onReady`() = loopTest { loop ->
        // Order matters for a combined data-and-EOF event: drain before close.
        val listener = RecordingListener()
        loop.registerCallback(FD, Interest.READ, listener)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = true)

        assertEquals(listOf(Interest.READ), listener.ready)
        assertEquals(listOf(Interest.READ), listener.peerClosed)
        assertEquals(listOf("onReady", "onPeerClosed"), listener.order)
    }

    @Test
    fun `eof does not disarm a listener that re-armed`() = loopTest { loop ->
        // The regression the comment on dispatchReady records: eof used to
        // disarm unconditionally, on the reasoning that a connection reporting
        // EOF is ending. A server's AcceptArm re-arms on both WouldBlock and a
        // failed accept, putting itself straight back into the registry -- so
        // disarming here discarded a live registration and left an accept loop
        // that never ran again.
        val listener = RecordingListener(reArmOn = loop)
        loop.registerCallback(FD, Interest.READ, listener)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = true)

        assertEquals(listOf(Interest.READ), listener.peerClosed, "the close still reaches the listener")
        assertEquals(emptyList(), loop.disarmed, "but a re-armed interest must survive it")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `eof is delivered on the write interest too`() = loopTest { loop ->
        // The base delivers eof on whichever interest the event arrived on, and
        // both engines do pass the flag on their write filter, so this pins the
        // dispatch contract rather than a transport outcome.
        //
        // No transport reacts to a WRITE eof today: both onPeerClosed overrides
        // return early on anything but READ. What the test holds is that the
        // base does not silently drop half the parameter's domain.
        val listener = RecordingListener()
        loop.registerCallback(FD, Interest.WRITE, listener)

        loop.dispatchReadyFor(FD, Interest.WRITE, eofFlag = true)

        assertEquals(listOf(Interest.WRITE), listener.peerClosed, "peer close must reach a write-only listener")
        assertEquals(listOf("onReady", "onPeerClosed"), listener.order)
        // The same assertion the non-eof sibling makes. Without it, skipping the
        // disarm when eofFlag is set passes here -- which is the pre-#449 bug
        // inverted, and this branch used to be written separately per engine.
        assertEquals(listOf(FD to Interest.WRITE), loop.disarmed, "and the interest is still taken back")
    }

    @Test
    fun `a listener that re-arms during onPeerClosed keeps its interest`() = loopTest { loop ->
        // The later of the two re-arm points, and the one the sibling tests
        // cannot see: their listener re-arms during onReady, so they hold the
        // probe only against being moved before that. This one holds it against
        // being moved between the two callbacks.
        //
        // No in-tree listener re-arms from onPeerClosed today -- SuspendBridgeHandler
        // deliberately does not, and a test asserts that. So this pins the contract
        // the interface states (a listener may re-arm from either callback) rather
        // than a live path, and it is the contract that makes the probe's position
        // load-bearing for anyone who writes such a listener.
        val listener = ReArmOnPeerClosedListener(loop)
        loop.registerCallback(FD, Interest.READ, listener)

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = true)

        assertEquals(listOf(Interest.READ), listener.peerClosed)
        assertEquals(emptyList(), loop.disarmed, "a re-arm from onPeerClosed must survive the probe")
        assertTrue(loop.hasCallbackRegistration(FD, Interest.READ))
    }

    @Test
    fun `readiness pops one suspend waiter and leaves the interest armed for its siblings`() =
        loopTest { loop ->
            // The suspend arm of dispatchReady, which the callback tests never
            // reach. Two waiters on one key is the concurrent-accept() shape: the
            // first is resumed, the interest stays armed so the next wait
            // cascade-fires the second.
            val first = suspendOn(loop, FD, Interest.READ).await()
            val second = suspendOn(loop, FD, Interest.READ).await()

            loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

            // await rather than isCompleted: the resume schedules the waiter's
            // coroutine, which has to run before it completes its handle.
            first.resumed.await()
            yield()
            assertFalse(second.resumed.isCompleted, "its sibling waits for the next event")
            assertEquals(emptyList(), loop.disarmed, "and the interest stays armed while it does")
        }

    @Test
    fun `readiness takes the interest back once the last suspend waiter is gone`() = loopTest { loop ->
        // The other side of the same decision: with the chain empty there is
        // nothing to cascade to, so leaving it armed is the level-triggered busy
        // loop the KDoc describes.
        val only = suspendOn(loop, FD, Interest.READ).await()

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        only.resumed.await()
        assertEquals(listOf(FD to Interest.READ), loop.disarmed, "the last waiter takes the interest with it")
    }

    @Test
    fun `readiness with no handler at all disarms and warns`() = loopTest { loop ->
        // The stale-interest safety net: nothing registered, so the kernel would
        // keep re-firing until the fd closed.
        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(listOf(FD to Interest.READ), loop.disarmed)
        assertTrue(
            loop.warnings.any { it.contains("no handler") },
            "the broken invariant must be visible: ${loop.warnings}",
        )
    }

    @Test
    fun `readiness prefers the callback over a suspend waiter on the same key`() = loopTest { loop ->
        // Precedence only: the callback wins the dispatch and the waiter stays
        // queued. What happens to the interest when the callback declines to
        // re-arm is the sibling test below.
        val listener = RecordingListener(reArmOn = loop)
        loop.registerCallback(FD, Interest.READ, listener)
        val waiter = suspendOn(loop, FD, Interest.READ).await()

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(listOf(Interest.READ), listener.ready)
        assertTrue(loop.waiters(FD, Interest.READ), "the suspend waiter must still be queued")
        assertFalse(waiter.resumed.isCompleted)
    }

    @Test
    fun `a callback that does not re-arm leaves the interest for a waiting sibling`() = loopTest { loop ->
        // The callback wins the dispatch and declines to re-arm, but a suspend
        // waiter is queued on the same key. Taking the interest back here strands
        // it: nothing re-arms, so its continuation is never resumed and never
        // failed.
        loop.registerCallback(FD, Interest.READ, RecordingListener())
        val waiter = suspendOn(loop, FD, Interest.READ).await()

        loop.dispatchReadyFor(FD, Interest.READ, eofFlag = false)

        assertEquals(emptyList(), loop.disarmed, "a queued waiter still needs the interest armed")
        assertTrue(loop.waiters(FD, Interest.READ), "and it is still in the chain")
        assertFalse(waiter.resumed.isCompleted)
    }

    @Test
    fun `the sweep withdraws every callback the loop will never dispatch`() = loopTest { loop ->
        // The pipeline half of what the suspend sweep does. A listener left in
        // the ledger is not merely un-notified: it holds the transport, and the
        // transport holds the channel and the pipeline graph behind it, for as
        // long as the stopped loop object is alive.
        val listener = RecordingListener()
        loop.registerCallback(FD, Interest.READ, listener)
        loop.registerCallback(FD, Interest.WRITE, listener)

        loop.failRemainingWaiters()

        assertFalse(loop.hasCallbackRegistration(FD, Interest.READ), "the callback ledger is emptied")
        assertFalse(loop.hasCallbackRegistration(FD, Interest.WRITE))
    }

    @Test
    fun `the sweep tells a participant once however many registrations it holds`() = loopTest { loop ->
        // Once per participant, not once per registration. Stopping is a
        // lifecycle event: which entries the participant happened to hold
        // changes nothing about it, and the old per-registration double call
        // was an artifact of keying the notification on the ledger.
        val listener = RecordingListener()
        loop.addParticipant(listener)
        loop.registerCallback(FD, Interest.READ, listener)
        loop.registerCallback(FD, Interest.WRITE, listener)

        loop.failRemainingWaiters()

        assertEquals(1, listener.loopStopped, "told exactly once")
        assertEquals(emptyList(), listener.ready, "the sweep is not a readiness dispatch")
        assertEquals(emptyList(), listener.peerClosed, "and it is not a peer close")
    }

    @Test
    fun `the sweep tells a participant holding no registration at all`() = loopTest { loop ->
        // The connection this registry exists for. A paused connection holds no
        // registration -- its one-shot entry was consumed and the
        // back-pressured re-arm declined -- yet it is the one most likely to be
        // waiting on this loop, because keel's own flow control is what pauses
        // it. The ledger-keyed notification walked straight past it.
        val listener = RecordingListener()
        loop.addParticipant(listener)

        loop.failRemainingWaiters()

        assertEquals(1, listener.loopStopped, "a live participant is told even with an empty ledger")
    }

    @Test
    fun `removeParticipant ends the obligation to tell`() = loopTest { loop ->
        // The teardown half: a transport that closed cleanly must not be told
        // its loop stopped afterwards -- it is gone, and telling it would run
        // teardown callbacks on an object that already ran them.
        val listener = RecordingListener()
        loop.addParticipant(listener)
        loop.removeParticipant(listener)

        loop.failRemainingWaiters()

        assertEquals(0, listener.loopStopped, "a removed participant is not told")
    }

    @Test
    fun `a participant joining after the sweep is refused with a warning`() = loopTest { loop ->
        // Same closure, same shape as the ledger refusals: the registry is
        // emptied and closed in one critical section, so a late joiner is never
        // silently retained by a registry nothing reads again. Refusal, not a
        // throw -- every transport constructor calls this, and none of the
        // construction sites closes its fd on a constructor throw, so a throw
        // here would trade a reported dead channel for a descriptor leak.
        loop.failRemainingWaiters()

        val late = RecordingListener()
        loop.addParticipant(late)

        loop.failRemainingWaiters()

        assertEquals(0, late.loopStopped, "a refused participant is not retained and not told")
        assertTrue(
            loop.warnings.any { it.contains("addParticipant") },
            "and the refusal is reported, not silent: ${loop.warnings}",
        )
    }

    @Test
    fun `the sweep drains what a listener queued even with nothing stranded`() {
        // The drain used to be gated on stranded suspend waiters alone. A
        // pipeline-only loop strands none -- a write-only client with
        // readEnabled = false is exactly one, and is the case this sweep exists
        // for -- so anything the notification queued was dropped on the floor.
        // Teardown does queue: it cancels a flush continuation whose resume
        // lands on this very queue, and this is the last drain there will be.
        val loop = FakeLoop(runDispatchedInline = false)
        var queuedRan = false
        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    loop.dispatch(EmptyCoroutineContext, Runnable { queuedRan = true })
                }
            },
        )

        loop.failRemainingWaiters()

        assertTrue(queuedRan, "the sweep's own drain has to deliver it; nothing runs after")
    }

    @Test
    fun `a listener that throws does not strand the rest of the sweep`() {
        // Same backstop drainQueue puts around a dispatched task, for the same
        // reason: this runs user code, and one bad listener must not take the
        // others -- nor escape a pthread entry point with nothing above it.
        // Fails either way when unguarded: the throw either reaches this caller
        // or the healthy listener never hears, depending on iteration order.
        val loop = FakeLoop()
        val healthy = RecordingListener()
        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped(): Unit = throw IllegalStateException("boom")
            },
        )
        loop.addParticipant(healthy)

        loop.failRemainingWaiters()

        assertEquals(1, healthy.loopStopped, "the healthy participant is still told")
    }

    @Test
    fun `a negative fd keys its two interests apart in the ledger`() {
        // A negative fd sign-extends through the key's interest half, so without
        // the mask in registrationKey both interests hash to the same key and
        // the WRITE registration replaces the READ one.
        //
        // Probed by *identity*, deliberately. A presence probe
        // (hasCallbackRegistration) re-derives its key through the very
        // registrationKey being pinned, so under a broken mask both probes find
        // the one collided entry and pass -- measured: with the mask removed,
        // that form left all tests green. Identity survives the shared
        // derivation: under a collision the slot holds the *wrong* listener,
        // whichever key reaches it.
        val loop = FakeLoop()
        val readListener = RecordingListener()
        val writeListener = RecordingListener()
        loop.registerCallback(-1, Interest.READ, readListener)
        loop.registerCallback(-1, Interest.WRITE, writeListener)

        assertTrue(
            loop.popIfCurrent(loop.keyFor(-1, Interest.READ), readListener),
            "the READ slot must still hold the READ listener; a collision replaced it with WRITE's",
        )
        assertTrue(
            loop.popIfCurrent(loop.keyFor(-1, Interest.WRITE), writeListener),
            "and the WRITE slot its own",
        )
    }

    @Test
    fun `a participant may take the registration lock from onLoopStopped`() {
        // Why the notification runs outside withRegLock. The real path re-enters:
        // onLoopStopped -> onReadClosed -> close() -> teardownOnEventLoop ->
        // unregisterCallback -> withRegLock, on a mutex initialised with default
        // attributes, so it is not recursive. Moving the notification inside the
        // lock does not fail this test -- it hangs it, and every pipeline
        // transport with it, which is the point.
        val loop = FakeLoop()
        var reEntered = false
        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    loop.unregisterCallback(FD, Interest.WRITE)
                    reEntered = true
                }
            },
        )

        loop.failRemainingWaiters()

        assertTrue(reEntered, "the participant reached a lock-taking call and returned")
    }

    @Test
    fun `the sweep is a no-op when nothing is waiting`() = loopTest { loop ->
        loop.failRemainingWaiters()
        assertFalse(loop.waiters(FD, Interest.READ))
    }

    @Test
    fun `the sweep delivers the resume of a waiter dispatched on this loop`() = runBlocking {
        withTimeout(TEST_BUDGET) {
            // The case the sweep exists for, wired the way production wires it:
            // keel launches every connection handler on `channel.ioDispatcher`,
            // which is the EventLoop, so a connect() from there parks a
            // continuation whose resume comes back through this dispatch().
            // Cancelling only queues that resume -- if the loop stops without
            // draining again, the caller is cancelled and still parked.
            val loop = FakeLoop(runDispatchedInline = false)
            val waiters = CoroutineScope(coroutineContext + Job())
            try {
                val resumed = CompletableDeferred<Unit>()
                waiters.launch(loop) {
                    try {
                        suspendCancellableCoroutine { cont ->
                            val reg = loop.registerWaiter(FD, Interest.WRITE, cont)
                            cont.invokeOnCancellation { loop.unregister(reg) }
                        }
                        resumed.complete(Unit)
                    } catch (t: Throwable) {
                        resumed.completeExceptionally(t)
                    }
                }
                loop.drainDispatched() // start it; it registers and parks
                assertTrue(loop.waiters(FD, Interest.WRITE), "the waiter is in the ledger")

                loop.failRemainingWaiters()

                assertTrue(resumed.isCompleted, "the sweep must deliver the resume, not just queue it")
                assertSweptFailure(resumed)
                assertFalse(loop.waiters(FD, Interest.WRITE), "the ledger is emptied")
            } finally {
                waiters.cancel()
                // Before the join, and the reason this teardown differs from
                // loopTest's: nothing here runs dispatched work on its own, so
                // a waiter whose resume is still queued never completes, and a
                // NonCancellable join on it hangs instead of letting the failed
                // assertion be reported.
                loop.drainDispatched()
                withContext(NonCancellable) { waiters.coroutineContext.job.join() }
            }
        }
    }
}
