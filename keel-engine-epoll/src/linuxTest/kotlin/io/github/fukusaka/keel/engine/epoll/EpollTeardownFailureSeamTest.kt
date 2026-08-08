package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
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
 * Every test here asserts first that the seam *reached* the code it is about.
 * A teardown that never entered the drain would satisfy an assertion about what
 * the drain costs, and say nothing.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollTeardownFailureSeamTest {

    private val logger = NoopLoggerFactory.logger("EpollTeardownFailureSeamTest")
    private lateinit var eventLoop: EpollEventLoop
    private var readFd: Int = -1
    private var writeFd: Int = -1

    @BeforeTest
    fun setUp() {
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
        withTimeout(IO_BUDGET) { done.await() }
    }

    private fun newTransport(fake: FakeNativeSocket): EpollIoTransport =
        EpollIoTransport(readFd, eventLoop, DefaultAllocator, fake).also {
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

    private companion object {
        /**
         * Wall-clock budget for anything that goes through the loop. Matches the
         * envelope the sibling seam tests use for a loopback dispatch hop.
         */
        val IO_BUDGET = kotlin.time.Duration.parse("15s")
        const val POLL_MS = 10L
        const val PAYLOAD = 8
    }
}
