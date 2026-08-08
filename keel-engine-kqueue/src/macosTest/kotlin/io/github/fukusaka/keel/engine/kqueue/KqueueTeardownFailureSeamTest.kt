package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.Interest
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
class KqueueTeardownFailureSeamTest {

    private val logger = NoopLoggerFactory.logger("KqueueTeardownFailureSeamTest")
    private lateinit var eventLoop: KqueueEventLoop
    private var readFd: Int = -1
    private var writeFd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = KqueueEventLoop(logger)
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
        withTimeout(IO_BUDGET) { done.await() }
    }

    private fun newTransport(fake: FakeNativeSocket): KqueueIoTransport =
        KqueueIoTransport(readFd, eventLoop, DefaultAllocator, fake).also {
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
            withTimeout(IO_BUDGET) {
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
            withTimeout(IO_BUDGET) { waiter.await() }
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
            val transport = KqueueIoTransport(readFd, eventLoop, tracker, fake)
            val failing = FailingReleaseIoBuf(
                tracker.allocate(PAYLOAD).also { it.writerIndex = PAYLOAD },
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
            withTimeout(IO_BUDGET) {
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
                tracker.outstandingCount,
                "and nothing else may be left outstanding once the refused buffer is accounted for",
            )
        }
    }

    private companion object {
        /**
         * Wall-clock budget for anything that goes through the loop. Matches the
         * envelope the sibling seam tests use for a loopback dispatch hop.
         */
        val IO_BUDGET = 15.seconds

        /**
         * Budget for the one wait whose failure this file is about. Shorter
         * than [IO_BUDGET] on purpose: it has to expire first for the failure
         * to say which wait it was.
         */
        val WAITER_BUDGET = 5.seconds
        const val POLL_MS = 10L
        const val PAYLOAD = 8
    }
}
