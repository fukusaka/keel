@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.native.readiness.Interest
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.ReadinessIoTransport
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.posix.F_GETFD
import platform.posix.close
import platform.posix.fcntl
import platform.posix.pipe
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The seam for a teardown whose own work fails.
 *
 * `teardownOnEventLoop` owes several things — drain a deferred flush, release
 * the queued buffers, wake a caller parked in `awaitPendingFlush`, withdraw the
 * registrations and close the fd — and each of them can throw: the drain
 * reaches an allocator and a raw pointer, and a release reaches whatever
 * allocator produced the buffer. None of that was reachable from a test, so the
 * only check on it was reading the code, which over three attempts kept
 * producing a different defect.
 *
 * Two things make it reachable here. **The scenario is driven from the
 * EventLoop thread**, so `flush()` leaves `flushScheduled` set and the very next
 * `close()` runs the teardown inline and finds it — no race with the flush task
 * the dispatcher queued. And the failures are injected at the two places that
 * can really fail: [FakeNativeSocket.flushThrowsOnce] for the drain,
 * [FailingReleaseIoBuf] for the release.
 *
 * `teardownAfterLoopStopped` is covered here too, and is reached the other way
 * round: from the *test* thread, after the loop is gone. That is the only way
 * in — `close()` hands the teardown to the loop for as long as there is one,
 * and takes this branch only once the loop is quiescent — so none of the
 * paragraph above applies to it. It has no drain to fail, which makes the
 * release its first failure point, and it is the last thing able to end a
 * flush wait the stop sweep did not.
 *
 * Every test here asserts first that the seam *reached* the code it is about.
 * A teardown that never entered the drain would satisfy an assertion about what
 * the drain costs, and say nothing.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollTeardownFailureSeamTest {

    private lateinit var logger: RecordingLogger
    private lateinit var eventLoop: EpollEventLoop
    private var readFd: Int = -1
    private var writeFd: Int = -1

    @BeforeTest
    fun setUp() {
        logger = RecordingLogger()
        eventLoop = EpollEventLoop(logger)
        eventLoop.start()
        val fds = IntArray(2)
        val ok = fds.usePinned { pinned -> pipe(pinned.addressOf(0)) == 0 }
        check(ok) { "pipe() failed" }
        readFd = fds[0]
        writeFd = fds[1]
    }

    @AfterTest
    fun tearDown() {
        close(writeFd)
        // Every test here hands readFd to a transport that closes it.
        if (readFd >= 0) close(readFd)
        eventLoop.close()
    }

    /** Runs [body] on the EventLoop thread and waits for it. */
    private suspend fun onLoop(body: () -> Unit) {
        val done = CompletableDeferred<Unit>()
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                try {
                    body()
                } finally {
                    done.complete(Unit)
                }
            },
        )
        withTimeout(WAITER_BUDGET) { done.await() }
    }

    private fun newTransport(
        fake: FakeNativeSocket,
        allocator: BufferAllocator = DefaultAllocator,
    ): ReadinessIoTransport =
        ReadinessIoTransport(readFd, eventLoop, allocator, fake).also {
            it.onChannelAttached()
            it.readEnabled = true
        }

    @Test
    fun `a drain that throws still closes the fd and withdraws the registrations`() = runBlocking {
        withTimeout(IO_BUDGET) {
            val fake = FakeNativeSocket()
            val transport = newTransport(fake)
            val buf = DefaultAllocator.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD }
            val surrendered = readFd
            readFd = -1 // the transport owns it from here

            onLoop {
                transport.write(buf)
                // Leaves flushScheduled set: the dispatched flush task is queued
                // behind this one, and close() below runs the teardown inline.
                transport.flush()
                fake.flushThrowsOnce = InjectedFault("the deferred flush failed")
                transport.close()
            }

            // The seam reached the drain: teardown called flush, and the fake
            // threw from it. Without this the rest of the test is vacuous.
            assertEquals(
                1,
                fake.writeCalls + fake.writevCalls,
                "teardown must have drained the deferred flush for this test to mean anything",
            )
            assertEquals(
                null,
                fake.flushThrowsOnce,
                "the injected drain failure must have been consumed",
            )

            assertEquals(
                -1,
                fcntl(surrendered, F_GETFD),
                "a drain that threw must not strand the descriptor: the teardown claim is spent",
            )
            assertTrue(
                !eventLoop.hasCallbackRegistration(surrendered, Interest.READ),
                "nor leave a ledger entry naming an fd that is gone",
            )
        }
    }

    @Test
    fun `a release that throws still wakes a caller parked in awaitPendingFlush`() = runBlocking {
        withTimeout(IO_BUDGET) {
            val fake = FakeNativeSocket()
            val transport = newTransport(fake)
            val failing = FailingReleaseIoBuf(
                DefaultAllocator.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD },
            )
            val surrendered = readFd
            readFd = -1

            onLoop { transport.write(failing) }

            val waiter = CompletableDeferred<Unit>()
            val waiting = launch {
                try {
                    transport.awaitPendingFlush()
                } finally {
                    waiter.complete(Unit)
                }
            }
            withTimeout(WAITER_BUDGET) {
                while (!transport.hasFlushWaiter()) delay(POLL_MS)
            }

            // No flush scheduled, so the drain is a no-op and the release is the
            // first thing that can fail.
            onLoop { transport.close() }

            assertEquals(
                1,
                failing.refusedReleases,
                "the seam must have reached the release for this test to mean anything",
            )
            // Its own budget, inside the enclosing one, for the reason the
            // stopped-loop test below has one: sharing IO_BUDGET means the
            // outer deadline always expires first and the failure names nothing.
            val woken = withTimeoutOrNull(WAITER_BUDGET) { waiter.await() } != null
            assertTrue(woken, "the refused release must not take the waiter's wake with it")
            waiting.cancel()
            failing.releaseUnderlying()

            assertEquals(
                -1,
                fcntl(surrendered, F_GETFD),
                "a release that threw must not strand the descriptor either",
            )
        }
    }

    @Test
    fun `a release that throws after the loop stopped still wakes the parked caller`() = runBlocking {
        withTimeout(IO_BUDGET) {
            val fake = FakeNativeSocket()
            // Deliberately not attached, unlike `newTransport`: the stop sweep
            // only walks participants, and a transport joins when its channel
            // attaches. One that never joined keeps its parked waiter across
            // the sweep, which is the state this teardown is the last chance to
            // end. Production reaches the same state by racing instead --
            // `markClosing()` flips `opened` before the sweep gets here, and
            // `onLoopStopped` returns on that first line -- so the route taken
            // here is the deterministic one to an identical starting point.
            val tracker = TrackingAllocator()
            val transport = ReadinessIoTransport(readFd, eventLoop, tracker, fake)
            val failing = FailingReleaseIoBuf(
                tracker.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD },
            )
            // A second buffer behind the one that refuses. Without it the only
            // buffer in the test is the one the test itself releases at the
            // end, and every count would come out right whatever the teardown
            // did.
            val trailing = tracker.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD }
            val surrendered = readFd
            readFd = -1

            onLoop {
                transport.write(failing)
                transport.write(trailing)
            }

            val waiter = CompletableDeferred<Unit>()
            val waiting = launch {
                try {
                    transport.awaitPendingFlush()
                } finally {
                    waiter.complete(Unit)
                }
            }
            withTimeout(WAITER_BUDGET) {
                while (!transport.hasFlushWaiter()) delay(POLL_MS)
            }

            eventLoop.close()
            // The premise, asserted rather than assumed: a sweep that had ended
            // this wait would leave the teardown nothing to fail to do, and
            // every assertion below would hold against a build that never
            // staged it.
            assertTrue(
                transport.hasFlushWaiter(),
                "the sweep must have left the waiter for the teardown to find",
            )
            // And which teardown: everything asserted below holds under the
            // on-loop one too, so without this the test silently becomes a
            // duplicate of the one above it if close() ever returns before
            // quiescence.
            assertTrue(
                eventLoop.isStopped(),
                "close() must have left the loop quiescent, or the stopped-loop teardown is not what runs",
            )

            // Quiescent, so close() runs the stopped-loop teardown inline on
            // this thread -- and re-raises what the release refused.
            val raised = runCatching { transport.close() }.exceptionOrNull()

            assertEquals(
                1,
                failing.refusedReleases,
                "the seam must have reached the release for this test to mean anything",
            )
            assertTrue(
                raised is InjectedFault,
                "the refused release must still reach the caller, got: $raised",
            )
            // A budget of its own, well inside the enclosing one, so a teardown
            // that skips the cancel fails here -- naming the waiter -- rather
            // than at the outer deadline, whose message is the same one an
            // unrelated hang produces.
            val woken = withTimeoutOrNull(WAITER_BUDGET) { waiter.await() } != null
            assertTrue(woken, "the refused release must not take the waiter's wake with it")
            waiting.cancel()
            failing.releaseUnderlying()

            assertEquals(
                -1,
                fcntl(surrendered, F_GETFD),
                "nor may a release that threw strand the descriptor on this path",
            )
            assertEquals(
                0,
                transport.pendingByteCount(),
                "the ledger must be zeroed even though the release ahead of it threw",
            )
            // The drain finishes past a refusal now, so the buffer behind the
            // one that refused is released by the teardown rather than left to
            // this test. `failing`'s own delegate is the only one outstanding,
            // and the line above has just handed it back.
            assertEquals(
                0,
                tracker.outstandingCount,
                "the drain must finish past the refusal rather than abandon what is behind it",
            )
        }
    }

    @Test
    fun `a stalled drain leaves neither idle timer behind the teardown`() = runBlocking {
        withTimeout(IO_BUDGET) {
            // The drain used to arm a timer: a flush that stalls re-registers
            // for write readiness, and that starts the write-idle clock. With
            // the cancels ahead of the drain, the timer armed here outlived
            // the teardown -- holding this transport, and the channel and
            // pipeline graph behind it, on the loop's scheduler until it
            // fired. The arm now declines outright during a teardown (opened
            // is already false), so these assertions pin that decline on the
            // teardown route; the stage order stays as defence in depth. Not a
            // second inactive notification: the pipeline's is idempotent, so a
            // stray one is either swallowed or, after a local close, the first.
            val fake = FakeNativeSocket().apply { enqueueWrite(readFd, WriteResult.WouldBlock) }
            val transport = ReadinessIoTransport(
                readFd,
                eventLoop,
                DefaultAllocator,
                fake,
                idleTimeoutMillis = IDLE_TIMEOUT_MS,
            ).also { it.onChannelAttached() }
            val buf = DefaultAllocator.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD }
            val reported = CompletableDeferred<Unit>()
            transport.onReadClosed = { reported.complete(Unit) }
            val readFdBeforeSurrender = readFd
            readFd = -1

            onLoop {
                // Arms the read-side deadline too, which is the only way this
                // test can see whether the teardown cancels that one: it is
                // armed from this setter and from nowhere else. On the loop,
                // because the setter reaches the loop's own deadline scheduler,
                // which is documented as having no thread safety at all.
                transport.readEnabled = true
                transport.write(buf)
                // Leaves flushScheduled set, so the teardown finds a drain to run.
                transport.flush()
                transport.close()
            }

            // The seam reached the stall: the drain wrote and got WouldBlock,
            // the point where a live flush would arm. Without this the wait
            // below passes against a teardown that never drained at all.
            assertEquals(
                1,
                fake.writeCalls + fake.writevCalls,
                "teardown must have attempted the deferred flush for this test to mean anything",
            )
            assertTrue(
                !eventLoop.hasCallbackRegistration(readFdBeforeSurrender, Interest.WRITE),
                "and the stall must not have left a WRITE registration behind",
            )

            // Long enough that a surviving timer has fired: it is scheduled for
            // IDLE_TIMEOUT_MS, and the loop is still running to fire it.
            delay(IDLE_TIMEOUT_MS * TIMER_WAIT_FACTOR)
            assertTrue(
                !reported.isCompleted,
                "a timer the teardown left armed spoke for a connection that was already gone",
            )
        }
    }

    @Test
    fun `a stalled flush arms the write-idle timer and registers for write readiness`() = runBlocking {
        withTimeout(IO_BUDGET) {
            // The other half of the test above, which asserts two absences:
            // without this, a fixture that never armed a timer or never
            // registered would satisfy both, and so would a
            // `registerWriteCallback` that stopped doing either. The stall is
            // reached through the ordinary coalesced flush here rather than
            // through a teardown's drain -- a different call site, converging
            // on the same `WouldBlock` -> `registerWriteCallback`.
            val fake = FakeNativeSocket().apply { enqueueWrite(readFd, WriteResult.WouldBlock) }
            val transport = ReadinessIoTransport(
                readFd,
                eventLoop,
                DefaultAllocator,
                fake,
                idleTimeoutMillis = IDLE_TIMEOUT_MS,
            ).also { it.onChannelAttached() }
            val buf = DefaultAllocator.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD }
            val reported = CompletableDeferred<Unit>()
            transport.onReadClosed = { reported.complete(Unit) }
            // The timeout closes the connection itself, which is what releases
            // the descriptor here -- no close() of our own.
            val surrendered = readFd
            readFd = -1

            onLoop {
                transport.write(buf)
                transport.flush()
            }

            // Polled, not read once: `flush()` coalesces, so the registration
            // is made by a Runnable dispatched behind the one `onLoop` waited
            // for -- and the timeout withdraws it again at IDLE_TIMEOUT_MS, so
            // a single read races both ends.
            // Bounded by the timeout that ends this connection, not by the
            // enclosing budget: the registration exists only between the
            // stalled flush and the teardown that withdraws it, so a poll that
            // misses the window would otherwise spin to the outer deadline and
            // fail with a message naming nothing.
            var registered = false
            withTimeout(WAITER_BUDGET) {
                while (!registered && !reported.isCompleted) {
                    registered = eventLoop.hasCallbackRegistration(surrendered, Interest.WRITE)
                    if (!registered) delay(POLL_MS)
                }
            }
            assertTrue(registered, "the stall must register for write readiness before the timeout withdraws it")
            // Its own budget, as the waiter waits above have: nested inside
            // the enclosing one with the same value, it could never expire
            // first and the failure would name nothing.
            assertTrue(
                withTimeoutOrNull(WAITER_BUDGET) { reported.await() } != null,
                "the stall must arm a write-idle timer, or the test above asserts an absence that proves nothing",
            )
        }
    }

    @Test
    fun `the first stage failure is raised and the later one is attached to it`() = runBlocking {
        withTimeout(IO_BUDGET) {
            // Two stages fail in one teardown. Which one the connection dies
            // reporting is the whole reason this is stages and not nested
            // `finally` blocks -- a throw from a `finally` discards the
            // exception that entered it, so the release failure would replace
            // the drain failure that started the wind-down. Nothing held that
            // until now: every other test here fails exactly one stage, so a
            // rewrite to last-failure-wins would leave them all green.
            val fake = FakeNativeSocket()
            // Tracked, so the drain behind the refusal is asserted here too and
            // not only on the stopped-loop path: this is the on-loop teardown,
            // and it uses the same shared drain.
            val tracker = TrackingAllocator()
            val transport = newTransport(fake, tracker)
            val failing = FailingReleaseIoBuf(
                tracker.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD },
            )
            // Two queued, so the drain gathers. The second entry is what the
            // refusal used to abandon, and the count below is what says it no
            // longer does. (The single path used to remove its entry before
            // writing, leaving the release stage nothing to fail on -- it
            // peeks now, so one buffer would reach that stage too, but the
            // gather walk is the shape under test here.)
            val trailing = tracker.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD }
            readFd = -1
            var raised: Throwable? = null

            onLoop {
                transport.write(failing)
                transport.write(trailing)
                transport.flush()
                fake.flushThrowsOnce = InjectedFault("the deferred flush failed")
                raised = runCatching { transport.close() }.exceptionOrNull()
            }

            // Both stages were really entered, or the ordering below is about
            // nothing.
            assertEquals(null, fake.flushThrowsOnce, "the drain must have thrown for this test to mean anything")
            // Through the gather path specifically: the single-write path would
            // have taken its entry out of the deque first, leaving the release
            // stage nothing to refuse -- which is the premise the two buffers
            // above exist for.
            assertEquals(1, fake.writevCalls, "and it must have thrown from the gather path")
            assertEquals(1, failing.refusedReleases, "and the release after it must have thrown too")

            val first = raised
            assertTrue(
                first is InjectedFault && first.message == "the deferred flush failed",
                "the connection must die reporting the failure that started the teardown, got: $raised",
            )
            assertTrue(
                first.suppressedExceptions.any { it is InjectedFault },
                "and the later failure must be attached to it rather than dropped: ${first.suppressedExceptions}",
            )
            assertTrue(failing.releaseUnderlying(), "the test still owns the buffer whose release refused")
            assertEquals(
                0,
                tracker.outstandingCount,
                "the drain must finish past the refusal here too, not only after the loop stopped",
            )
        }
    }

    @Test
    fun `an inactivity report that throws still reclaims the connection`() = runBlocking {
        withTimeout(IO_BUDGET) {
            // The idle timeout exists to take a descriptor back from a peer that
            // has stopped cooperating, and it announces that before it acts. The
            // announcement runs user code -- every handler's `onInactive` -- and
            // a throw out of it used to reach the timer's own guard, which
            // reports and moves on. The close never ran, so the fd the timeout
            // exists to reclaim stayed open for the process lifetime: the exact
            // outcome this timeout is the last defence against, defeated by the
            // report it makes on the way.
            val fake = FakeNativeSocket()
            val transport = ReadinessIoTransport(
                readFd,
                eventLoop,
                DefaultAllocator,
                fake,
                idleTimeoutMillis = IDLE_TIMEOUT_MS,
            ).also { it.onChannelAttached() }
            val reported = CompletableDeferred<Unit>()
            transport.onReadClosed = {
                reported.complete(Unit)
                throw InjectedFault(REPORT_FAULT)
            }
            val surrendered = readFd
            readFd = -1

            // On the loop: the setter reaches the loop's own deadline scheduler.
            onLoop { transport.readEnabled = true }

            // The seam reached the report, or the descriptor below proves nothing.
            assertTrue(
                withTimeoutOrNull(WAITER_BUDGET) { reported.await() } != null,
                "the idle timeout must have fired and reported for this test to mean anything",
            )
            withTimeout(WAITER_BUDGET) {
                while (fcntl(surrendered, F_GETFD) != -1) delay(POLL_MS)
            }
        }
    }

    @Test
    fun `a report and a close that both fail are raised together`() = runBlocking {
        withTimeout(IO_BUDGET) {
            // Running the close regardless of the report costs nothing if the
            // close throws too: the report's failure is the earlier one, so it
            // is what reaches the guard, carrying the close's. Raising them
            // one-or-the-other would trade the defect above for a quieter one.
            val fake = FakeNativeSocket()
            val tracker = TrackingAllocator()
            val transport = ReadinessIoTransport(
                readFd,
                eventLoop,
                tracker,
                fake,
                idleTimeoutMillis = IDLE_TIMEOUT_MS,
            ).also { it.onChannelAttached() }
            // Queued and never flushed, so the teardown the close runs reaches
            // its release, is refused, and the close throws in its turn.
            val failing = FailingReleaseIoBuf(
                tracker.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD },
            )
            transport.onReadClosed = { throw InjectedFault(REPORT_FAULT) }
            val surrendered = readFd
            readFd = -1

            onLoop {
                transport.write(failing)
                transport.readEnabled = true
            }

            // The scheduler's guard is the only observer either failure has --
            // it catches what a timer task raises, warns, and moves on to the
            // next due timer -- so the fixture's logger is what this reads.
            val logged = withTimeoutOrNull(WAITER_BUDGET) { logger.firstWarning.await() }
            assertEquals(
                1,
                failing.refusedReleases,
                "the seam must have reached the release for this test to mean anything",
            )
            assertEquals(
                REPORT_FAULT,
                logged?.message,
                "the report failed first, so its failure is the one raised, got: $logged",
            )
            assertTrue(
                logged?.suppressedExceptions.orEmpty().any { it is InjectedFault && it.message != REPORT_FAULT },
                "and the close's must be attached to it: ${logged?.suppressedExceptions}",
            )
            assertTrue(failing.releaseUnderlying(), "the test still owns the buffer whose release refused")
            assertEquals(0, tracker.outstandingCount, "and nothing else was left behind")
            assertEquals(
                -1,
                fcntl(surrendered, F_GETFD),
                "a close that threw still owes the descriptor",
            )
        }
    }

    /**
     * Records the throwable the deadline scheduler's guard logs.
     *
     * What an idle timeout raises has no other observer: the timer task is
     * scheduled, not called, and the scheduler catches, warns, and continues.
     * A test that wants to know which failure came out of one has to read it
     * here.
     */
    private class RecordingLogger : Logger {
        val firstWarning = CompletableDeferred<Throwable>()

        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.WARN

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            // First one wins, and a later unrelated warning cannot displace it.
            // If an unrelated one gets here first the assertions fail on its
            // message rather than passing on silence.
            throwable?.let { firstWarning.complete(it) }
        }
    }

    private companion object {
        /**
         * The outer envelope, and nothing else. Every test here wraps its body
         * in this; nothing nested uses it, because a nested wait carrying the
         * enclosing budget starts later and so can never expire first, leaving
         * a failure that names nothing.
         */
        val IO_BUDGET = 15.seconds

        /**
         * Every wait nested inside [IO_BUDGET]: the dispatch hops, the polls,
         * and the waits whose failure this file is about. Shorter on purpose —
         * it has to expire first for the failure to say which wait it was.
         */
        val WAITER_BUDGET = 5.seconds
        const val POLL_MS = 10L
        const val PAYLOAD = 8

        /**
         * Short enough that the test does not sit on it, long enough that the
         * teardown finishes well before a surviving timer would fire — so a
         * failure means the timer outlived the teardown, not that it raced it.
         */
        const val IDLE_TIMEOUT_MS = 150L

        /** Multiple of [IDLE_TIMEOUT_MS] to wait before concluding nothing fired. */
        const val TIMER_WAIT_FACTOR = 4

        /**
         * What the injected inactivity report throws. Named because a test
         * below has to tell it apart from the refused release attached to it.
         */
        const val REPORT_FAULT = "the inactivity report failed"
    }
}
