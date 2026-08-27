@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.ShutdownResult
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.JoinRefusal
import io.github.fukusaka.keel.native.readiness.LoopParticipant
import io.github.fukusaka.keel.native.readiness.ReadinessIoTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EBADF
import platform.posix.close
import platform.posix.dup
import platform.posix.errno
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Seam tests for [ReadinessIoTransport] once its EventLoop has stopped — the
 * entry points that used to swallow work, and the teardown that has to run
 * on the caller instead.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueTransportStoppedLoopSeamTest : KqueueTransportSeamFixture() {

    // --- Close after the loop has stopped (caller-thread teardown) ---

    @Test
    fun `close after the loop stopped still releases the fd and the pending buffers`() = runBlocking {
        // A Coroutine-mode caller closes its channel after engine shutdown.
        // The loop is gone, so the teardown cannot be dispatched; it has to
        // run on the caller instead — otherwise the fd and the stranded
        // write buffers survive to process exit.
        eventLoop.start()
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply {
            // Strand one buffer in pendingWrites: the flush stalls on
            // WouldBlock and the loop stops before any retry.
            enqueueWrite(fd, WriteResult.WouldBlock)
        }
        val transport = ReadinessIoTransport(fd, eventLoop, tracker, fake)
        val queued = CompletableDeferred<Unit>()
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = tracker.allocate(SEAM_PAYLOAD_BYTES)
                buf.writerIndex = SEAM_PAYLOAD_BYTES
                transport.write(buf)
                transport.flush()
                queued.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { queued.await() }
        eventLoop.close()

        transport.close()

        val closedFd = fd
        fd = -1 // ownership passed to the transport, which closed it; tearDown must not close the number again
        val probe = dup(closedFd)
        if (probe >= 0) close(probe)
        assertEquals(-1, probe, "the fd must be closed on the caller when the loop is gone")
        assertEquals(EBADF, errno, "closed, not fd-table exhaustion: the probe must fail with EBADF")
        assertEquals(0, tracker.outstandingCount, "the stranded pending write must be released")
    }

    // --- registration refused by a swept loop ---

    @Test
    fun `a transport whose channel attaches after the sweep is refused rather than silently unheard`() = runBlocking {
        // The construction window: the transport object exists while the loop is
        // still running, and the callbacks it would be told through are wired a
        // moment later. Joining in the constructor put it in the registry for
        // that whole gap, so a sweep landing inside delivered the one and only
        // stop notification into a null `onReadClosed` and the connection was
        // never told -- while its construction site saw a joined transport and
        // handed the caller a channel that would stay silent.
        //
        // Joining when the channel attaches turns that into a refusal the site
        // can act on. This drives the window deliberately: build, sweep, attach.
        eventLoop.start()
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, FakeNativeSocket())
        eventLoop.close()

        transport.onChannelAttached()

        assertFalse(
            transport.joinedLoop,
            "a sweep between construction and attach must refuse the join, not swallow the notification",
        )
        assertEquals(
            JoinRefusal.LOOP_STOPPED,
            transport.joinRefusal,
            "named as the sweep it was: the accept site ends its loop for this cause and not the other",
        )

        transport.close()

        val closedFd = fd
        fd = -1 // the transport closed it; tearDown must not close the number again
        val probe = dup(closedFd)
        if (probe >= 0) close(probe)
        // The probe's own result first: a successful dup writes no errno, so
        // asserting errno alone reads whatever the preceding calls left behind
        // and passes for a descriptor that was never released. The sibling
        // above does it in this order for the same reason.
        assertEquals(-1, probe, "closing the refused transport must release its fd")
        assertEquals(EBADF, errno, "closed, not fd-table exhaustion: the probe must fail with EBADF")
    }

    // --- entry points a stopped loop used to swallow ---

    @Test
    fun `a FIN deferred behind buffered writes is reported when the loop stops first`() = runBlocking {
        // The half-close is held back until the writes drain, and the drain
        // needs a loop that polls. When the loop stops first, nothing calls
        // sendFinIfDrained again -- the FIN is never sent and, before this,
        // never mentioned either: the same "no FIN, no error, no log" the
        // quiescent case was fixed for, surviving in the one window that is
        // deliberately routed to dispatch.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = KqueueEventLoop(warns, flushCoalescing = false)
        loop.start()
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = ReadinessIoTransport(fd, loop, tracker, fake)
        // Joins the loop, as a real channel does: the stop notification goes
        // to participants, and a transport joins when its channel attaches.
        transport.onChannelAttached()

        // Buffered and stalled, then half-closed -- all on the loop, so the FIN
        // is genuinely deferred rather than refused.
        val deferred = CompletableDeferred<Unit>()
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = tracker.allocate(SEAM_PAYLOAD_BYTES).also { it.writerIndex = SEAM_PAYLOAD_BYTES }
                transport.write(buf)
                transport.shutdownOutput()
                deferred.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { deferred.await() }

        loop.close()

        assertEquals(0, fake.shutdownCalls, "premise: the FIN never went out")
        assertEquals(
            1,
            warns.messages.count { "deferred the FIN behind buffered writes" in it },
            "reported exactly once -- both discovery points run, and only one may claim it: ${warns.messages}",
        )
        assertStrandedWritesReleased(transport, tracker)
    }

    @Test
    fun `a queued flush that cannot drain reports the abandoned FIN itself`() = runBlocking {
        // The same window as the test below, but the queued write blocks. The
        // half-close deferred to that flush and stayed quiet because it was
        // still coming; the flush then cannot drain either, and it is the last
        // thing that could have sent the FIN. If it does not speak, nobody does
        // -- which is the silence this whole change is about.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = KqueueEventLoop(warns, flushCoalescing = true)
        loop.start()
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = ReadinessIoTransport(fd, loop, tracker, fake)
        // Joins the loop, as a real channel does -- this test turns on the
        // transport being in the registry alongside the participant below.
        transport.onChannelAttached()

        val buffered = CompletableDeferred<Unit>()
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = tracker.allocate(SEAM_PAYLOAD_BYTES).also { it.writerIndex = SEAM_PAYLOAD_BYTES }
                transport.write(buf)
                buffered.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { buffered.await() }

        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    transport.shutdownOutput()
                }
            },
        )

        loop.close()

        assertEquals(0, fake.shutdownCalls, "premise: the FIN never went out")
        assertEquals(
            1,
            warns.messages.count { "deferred the FIN behind buffered writes" in it },
            "the queued flush is the last chance, so it must report -- exactly once: ${warns.messages}",
        )
        assertStrandedWritesReleased(transport, tracker)
    }

    @Test
    fun `a half-close during the sweep waits for its queued flush before giving up`() = runBlocking {
        // The regression this guard exists for, in the only configuration that
        // can show it: the loop is finishing, so the report is armed, *and*
        // coalescing is on, so flush() has queued the write rather than
        // performing it. The queued Runnable still runs -- the sweep drains
        // again after walking the participants -- and it is what sends the FIN.
        // Giving up at the half-close would destroy a FIN that was about to go
        // out, and say so in a warning that is false.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = KqueueEventLoop(warns, flushCoalescing = true)
        loop.start()
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(SEAM_PAYLOAD_BYTES))
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = ReadinessIoTransport(fd, loop, tracker, fake)

        val buffered = CompletableDeferred<Unit>()
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = tracker.allocate(SEAM_PAYLOAD_BYTES).also { it.writerIndex = SEAM_PAYLOAD_BYTES }
                transport.write(buf)
                buffered.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { buffered.await() }

        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    transport.shutdownOutput()
                }
            },
        )

        loop.close()

        assertEquals(1, fake.shutdownCalls, "the queued flush must still get to send the FIN")
        assertTrue(
            warns.messages.none { "deferred the FIN behind buffered writes" in it },
            "nothing was abandoned, so nothing should be reported: ${warns.messages}",
        )
    }

    @Test
    fun `a FIN deferred on a running loop is not reported`() = runBlocking {
        // The negative case, and the one that decides whether this is a fix or
        // a log flood. Deferring a FIN behind buffered writes is the ordinary
        // half-close -- it is what the deferral is for -- and on a running loop
        // a completion path will still send it. Reporting here would mean a
        // warning per connection on every healthy server under backpressure.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = KqueueEventLoop(warns, flushCoalescing = false)
        loop.start()
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = ReadinessIoTransport(fd, loop, tracker, fake)
        try {
            val issued = CompletableDeferred<Unit>()
            loop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    val buf = tracker.allocate(SEAM_PAYLOAD_BYTES).also { it.writerIndex = SEAM_PAYLOAD_BYTES }
                    transport.write(buf)
                    transport.shutdownOutput()
                    issued.complete(Unit)
                },
            )
            withTimeout(SEAM_TIMEOUT_MS) { issued.await() }

            assertEquals(0, fake.shutdownCalls, "premise: the FIN is deferred, not sent")
            assertTrue(
                warns.messages.none { "deferred the FIN behind buffered writes" in it },
                "an ordinary deferral on a live loop must stay quiet: ${warns.messages}",
            )
        } finally {
            withTimeout(SEAM_TIMEOUT_MS) { loop.close() }
            // After the loop, not before: on a live loop close() hands the
            // teardown to it and returns, so the release would not have
            // happened yet. Once the loop is quiescent it runs on this thread.
            assertStrandedWritesReleased(transport, tracker)
        }
    }

    @Test
    fun `a coalesced flush queued behind a half-close still sends the FIN`() = runBlocking {
        // The regression the report can cause if it fires too early. With
        // coalescing on -- the default -- flush() does not write, it queues a
        // Runnable that writes on the next drain and sends the deferred FIN
        // itself. Giving the deferral up before that Runnable has had its turn
        // abandons a FIN that was about to go out.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = KqueueEventLoop(warns, flushCoalescing = true)
        loop.start()
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply {
            enqueueWrite(fd, WriteResult.Written(SEAM_PAYLOAD_BYTES))
            enqueueShutdown(fd, ShutdownResult.Ok)
        }
        val transport = ReadinessIoTransport(fd, loop, tracker, fake)

        val issued = CompletableDeferred<Unit>()
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = tracker.allocate(SEAM_PAYLOAD_BYTES).also { it.writerIndex = SEAM_PAYLOAD_BYTES }
                transport.write(buf)
                transport.shutdownOutput()
                issued.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { issued.await() }
        // Drained on *this* loop, not the fixture's: the coalesced Runnable was
        // queued here, and a marker on the other loop would never run.
        val drained = CompletableDeferred<Unit>()
        loop.dispatch(EmptyCoroutineContext, Runnable { drained.complete(Unit) })
        withTimeout(SEAM_TIMEOUT_MS) { drained.await() }
        // Closed before the assertions, and unconditionally: this loop watches
        // the fixture's fd, which tearDown closes. Leaving it running is the
        // recycled-fd hazard tearDown exists to avoid.
        withTimeout(SEAM_TIMEOUT_MS) { loop.close() }

        assertEquals(1, fake.shutdownCalls, "the queued flush must still get to send the FIN")
        assertTrue(
            warns.messages.none { "deferred the FIN behind buffered writes" in it },
            "and nothing was abandoned, so nothing should be reported: ${warns.messages}",
        )
        assertStrandedWritesReleased(transport, tracker)
    }

    @Test
    fun `a half-close taken during the stop sweep reports its abandoned FIN`() = runBlocking {
        // The other ordering, and the one the sweep cannot catch: the deferral
        // does not exist yet when the sweep walks this transport, and is created
        // afterwards -- by a participant closing its own output as it is told
        // the loop stopped. That runs on the loop thread with the loop already
        // finishing, so it takes the in-loop branch, which needs the report just
        // as much as the dispatched one.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = KqueueEventLoop(warns, flushCoalescing = false)
        loop.start()
        val tracker = TrackingAllocator()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = ReadinessIoTransport(fd, loop, tracker, fake)
        // Joins the loop, as a real channel does -- this test turns on the
        // transport being in the registry alongside the participant below.
        transport.onChannelAttached()

        val buffered = CompletableDeferred<Unit>()
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = tracker.allocate(SEAM_PAYLOAD_BYTES).also { it.writerIndex = SEAM_PAYLOAD_BYTES }
                transport.write(buf)
                buffered.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { buffered.await() }

        // Added after the transport joined (its channel attaches above), so the
        // sweep reaches the transport first -- while no deferral exists yet.
        // That order is what leaves this one for the half-close path, and it is
        // the whole point of the test: without the transport in the registry it
        // would pass through the half-close path unconditionally.
        loop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    transport.shutdownOutput()
                }
            },
        )

        loop.close()

        assertEquals(0, fake.shutdownCalls, "premise: the FIN never went out")
        assertTrue(
            warns.messages.any { "deferred the FIN behind buffered writes" in it },
            "a half-close taken during the sweep must report too: ${warns.messages}",
        )
        assertStrandedWritesReleased(transport, tracker)
    }

    @Test
    fun `shutdownOutput on a stopped loop is refused with a warning`() = runBlocking {
        // It used to dispatch onto a queue nothing drains: no FIN, no error, no
        // log -- the peer waited for an EOF that was never coming. The FIN still
        // is not sent (the buffered writes it waits for will never drain, and
        // issuing it here would race a concurrent close for the fd), but the
        // request is now reported instead of disappearing.
        val warns = RecordingLogger(LogLevel.WARN)
        val loop = KqueueEventLoop(warns, flushCoalescing = false)
        loop.start()
        val fake = FakeNativeSocket().apply { enqueueShutdown(fd, ShutdownResult.Ok) }
        val transport = ReadinessIoTransport(fd, loop, DefaultAllocator, fake)
        loop.close()

        // shutdownOutput() is non-blocking on every path, so this budget is a
        // real one rather than a formality -- it bounds the call itself, not
        // just the suite's house style.
        withTimeout(SEAM_TIMEOUT_MS) { transport.shutdownOutput() }

        assertEquals(0, fake.shutdownCalls, "no FIN is issued off the loop that used to order it")
        assertTrue(
            warns.messages.any { "shutdownOutput() on a stopped EventLoop" in it },
            "and the refusal must be reported, not silent: ${warns.messages}",
        )
    }

    @Test
    fun `awaitPendingFlush entered after the loop stopped is cancelled instead of parking`() = runBlocking {
        // The register Runnable would land on a dead queue, so the continuation
        // was never even stored -- the one shape close() cannot rescue, because
        // there is nothing for it to cancel.
        eventLoop.start()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)
        val queued = CompletableDeferred<Unit>()
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
                transport.write(buf)
                transport.flush()
                queued.complete(Unit)
            },
        )
        withTimeout(SEAM_TIMEOUT_MS) { queued.await() }
        eventLoop.close()

        var cancelled = false
        withTimeout(SEAM_TIMEOUT_MS) {
            try {
                transport.awaitPendingFlush()
            } catch (_: CancellationException) {
                cancelled = true
            }
        }

        assertTrue(cancelled, "a caller arriving after the loop stopped must not park forever")
    }

    @Test
    fun `the stop sweep ends a caller already parked in awaitPendingFlush`() = runBlocking {
        // Parked before the stop, on a live dispatcher, and never closed: the
        // sweep is the only thing that reaches it while the channel is open.
        eventLoop.start()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)
        // Joins the loop, as a real channel does: the stop notification goes
        // to participants, and a transport joins when its channel attaches.
        transport.onChannelAttached()
        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush()

        var cancelled = false
        val waiter = launch(Dispatchers.Default) {
            try {
                transport.awaitPendingFlush()
            } catch (_: CancellationException) {
                cancelled = true
            }
        }
        withTimeout(SEAM_TIMEOUT_MS) {
            while (!transport.hasFlushWaiter()) delay(SEAM_POLL_MS)
        }

        eventLoop.close() // runs the sweep, which tells every participant

        withTimeout(SEAM_TIMEOUT_MS) { waiter.join() }
        assertTrue(cancelled, "the sweep must end the write-side wait, not only the read side")
    }

    @Test
    fun `awaitPendingFlush on a stopped loop is cancelled with a reason rather than silently resumed`() = runBlocking {
        // Pins the deliberate choice, so it is not "improved" back into a race.
        // Deciding whether the queue is really empty means reading pending state
        // from off the loop, where a concurrent close() is emptying it -- and a
        // stale read there reports a flush complete whose bytes were dropped.
        // Cancelling is the fail-safe answer even for a caller that had nothing
        // queued, and the cause says which fd and why.
        eventLoop.start()
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, FakeNativeSocket())
        eventLoop.close()

        val cause = withTimeout(SEAM_TIMEOUT_MS) {
            runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
        }

        assertTrue(cause is CancellationException, "a stopped loop must not report the flush complete, got: $cause")
        assertTrue(
            cause.message?.contains("fd=$fd") == true,
            "the cancellation must name the fd and the reason, got: ${cause.message}",
        )
    }

    @Test
    fun `a flush awaited from inside the stop sweep is cancelled instead of parking`() = runBlocking {
        // The window the caller-side check cannot see. The loop has stopped
        // polling but is not yet quiescent, so work dispatched now still runs --
        // in the drain the sweep performs *after* it has walked the
        // participants. A continuation stored there is one the sweep has
        // already been past, so nothing is left to end the wait.
        eventLoop.start()
        val fake = FakeNativeSocket().apply { enqueueWrite(fd, WriteResult.WouldBlock) }
        val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, fake)
        val buf = DefaultAllocator.allocate(16).also { it.writerIndex = 4 }
        transport.write(buf)
        transport.flush()

        val outcome = CompletableDeferred<String>()
        eventLoop.addParticipant(
            object : LoopParticipant {
                override fun onLoopStopped() {
                    // Queued from inside the participant walk, so it lands in
                    // the drain that follows it.
                    CoroutineScope(eventLoop).launch {
                        try {
                            transport.awaitPendingFlush()
                            outcome.complete("resumed")
                        } catch (_: CancellationException) {
                            outcome.complete("cancelled")
                        }
                    }
                }
            },
        )

        eventLoop.close()

        assertEquals(
            "cancelled",
            withTimeout(SEAM_TIMEOUT_MS) { outcome.await() },
            "a flush awaited after the sweep has passed must not park",
        )
    }

    @Test
    fun `a stopped loop reports that it can no longer be dispatched to`() = runBlocking {
        // What the pipeline asks before handing over a buffer whose ownership
        // it has taken. The release itself is pinned in the pipeline's own
        // suite; this pins that this engine answers truthfully -- open, but
        // with a dispatcher that will never run anything again.
        //
        // The budget is declarative here: close() joins the loop thread with no
        // suspension point, so withTimeout cannot cut a loop that will not
        // leave its body -- the job timeout does. Stated anyway so the whole
        // suite reads one way.
        withTimeout(SEAM_TIMEOUT_MS) {
            eventLoop.start()
            val transport = ReadinessIoTransport(fd, eventLoop, DefaultAllocator, FakeNativeSocket())
            assertTrue(transport.canDispatchToOwningContext, "a live loop still takes work")

            eventLoop.close()

            assertTrue(transport.isOpen, "premise: the transport is still open, only its loop stopped")
            assertFalse(transport.canDispatchToOwningContext, "a stopped loop must say so")
        }
    }
}
