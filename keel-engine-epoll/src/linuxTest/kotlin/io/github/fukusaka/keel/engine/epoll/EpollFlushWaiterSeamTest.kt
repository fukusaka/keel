package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.socket
import platform.posix.usleep
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * That a caller waiting on a flush is answered when the drain *it* runs throws.
 *
 * `awaitPendingFlush` drains inline when a tick is still scheduled, and it does
 * that before storing its continuation anywhere. A throw escaping there leaves
 * a continuation nothing holds: not resumable by the write callback, not
 * reachable by the teardown's cancel, and — when the registration was
 * dispatched rather than run inline — swallowed by the loop's task drain. The
 * caller waits for the life of its job.
 *
 * **The dispatched case is the one that matters, and it is the ordinary one.**
 * A caller that dispatches its write and then awaits without waiting for it
 * leaves the loop queue as `[write, register]`; the write sets `flushScheduled`
 * and queues the tick *behind* the registration, so the registration reaches
 * the inline drain from another thread. This test builds that order by holding
 * the loop while the three tasks queue up.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal class EpollFlushWaiterSeamTest {

    private val logger = NoopLoggerFactory.logger("EpollFlushWaiterSeam")
    private lateinit var eventLoop: EpollEventLoop
    private var fd: Int = -1

    @BeforeTest
    fun setUp() {
        eventLoop = EpollEventLoop(logger)
        eventLoop.start()
        fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setUp" }
    }

    @AfterTest
    fun tearDown() {
        eventLoop.close()
        if (fd >= 0) close(fd)
    }

    @Test
    fun `an off-loop waiter that runs the drain itself is answered when it throws`() = runBlocking {
        withTimeout(BUDGET) {
            val fake = FakeNativeSocket().apply { flushThrowsOnce = InjectedFault("write refused") }
            val tracker = TrackingAllocator()
            val transport = EpollIoTransport(fd, eventLoop, tracker, fake)

            // Hold the loop so the two tasks below queue behind this one, in
            // the order they are dispatched.
            //
            // Bounded, and released from a `finally`. An unbounded hold whose
            // release is skipped by a throw would spin the loop thread for
            // good, and `tearDown`'s `close()` joins that thread -- a native
            // wait no `withTimeout` can interrupt once this coroutine has
            // unwound.
            val gate = AtomicInt(0)
            val held = AtomicInt(0)
            eventLoop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    held.store(1)
                    var spins = 0
                    while (gate.load() == 0 && spins < GATE_MAX_SPINS) {
                        usleep(GATE_POLL_US)
                        spins++
                    }
                },
            )
            val answer = CompletableDeferred<Throwable?>()
            try {
                eventLoop.dispatch(
                    EmptyCoroutineContext,
                    Runnable {
                        val buf = tracker.allocate(BUF_CAPACITY).also { it.writerIndex = PAYLOAD_BYTES }
                        transport.write(buf)
                        transport.flush() // queues the tick behind the registration below
                    },
                )

                // Unconfined so the body runs here up to its own dispatch: when
                // `launch` returns, the registration is already queued.
                launch(Dispatchers.Unconfined) {
                    answer.complete(runCatching { transport.awaitPendingFlush() }.exceptionOrNull())
                }
            } finally {
                gate.store(1)
            }

            // The fault, not just any throwable. The two arms above this branch
            // hand back a `CancellationException` -- a closed transport, or a
            // finishing loop -- and either would pass an "answered at all"
            // assertion while proving the guard was never reached.
            assertIs<InjectedFault>(answer.await(), "the waiter must be answered with the drain's failure")
            assertEquals(1, held.load(), "the loop was never held, so the queue order is not the one under test")
            assertTrue(fake.writeCalls >= 1, "the write must have been attempted for this test to mean anything")
            fake.assertAllConsumed()

            // The entry the guard put back is this test's to release: `close()`
            // is what walks the queue, and the barrier behind it is how we know
            // the teardown ran before the assertion.
            transport.close()
            val closed = CompletableDeferred<Unit>()
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { closed.complete(Unit) })
            closed.await()
            assertEquals(0, tracker.outstandingCount, "the teardown must release the re-queued entry")
        }
    }

    @Test
    fun `a parked waiter is answered when the tick that would resume it throws`() = runBlocking {
        withTimeout(BUDGET) {
            val fake = FakeNativeSocket()
            val tracker = TrackingAllocator()
            val transport = EpollIoTransport(fd, eventLoop, tracker, fake)

            // Buffered, not flushed: the waiter finds a non-empty queue with
            // nothing scheduled, so it parks rather than draining.
            val buffered = CompletableDeferred<Unit>()
            eventLoop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    val buf = tracker.allocate(BUF_CAPACITY).also { it.writerIndex = PAYLOAD_BYTES }
                    transport.write(buf)
                    buffered.complete(Unit)
                },
            )
            buffered.await()

            val answer = CompletableDeferred<Throwable?>()
            launch(Dispatchers.Unconfined) {
                answer.complete(runCatching { transport.awaitPendingFlush() }.exceptionOrNull())
            }
            val registered = CompletableDeferred<Unit>()
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { registered.complete(Unit) })
            registered.await()

            eventLoop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    fake.flushThrowsOnce = InjectedFault("write refused")
                    transport.flush() // schedules the tick that will throw
                },
            )

            assertIs<InjectedFault>(answer.await(), "the waiter must be given the drain's failure")

            transport.close()
            val closed = CompletableDeferred<Unit>()
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { closed.complete(Unit) })
            closed.await()
            assertEquals(0, tracker.outstandingCount, "the teardown must release the re-queued entry")
        }
    }

    private companion object {
        /** Any capacity above the payload; the flush reads the payload range. */
        const val BUF_CAPACITY = 16

        const val PAYLOAD_BYTES = 5

        const val GATE_POLL_US = 200u

        /** Bounds the hold at roughly [BUDGET] even if the release is skipped. */
        const val GATE_MAX_SPINS = 25_000

        /** The sibling seam suites' envelope; the happy path here is sub-second. */
        val BUDGET = 5.seconds
    }
}
