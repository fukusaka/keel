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
            val gate = AtomicInt(0)
            eventLoop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    while (gate.load() == 0) usleep(GATE_POLL_US)
                },
            )
            eventLoop.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    val buf = tracker.allocate(BUF_CAPACITY).also { it.writerIndex = PAYLOAD_BYTES }
                    transport.write(buf)
                    transport.flush() // queues the tick behind the registration below
                },
            )

            val answer = CompletableDeferred<Throwable?>()
            // Unconfined so the body runs here up to its own dispatch: when
            // `launch` returns, the registration is already queued.
            launch(Dispatchers.Unconfined) {
                answer.complete(runCatching { transport.awaitPendingFlush() }.exceptionOrNull())
            }
            gate.store(1)

            assertTrue(answer.await() != null, "the waiter must be answered, not parked")
        }
    }

    private companion object {
        /** Any capacity above the payload; the flush reads the payload range. */
        const val BUF_CAPACITY = 16

        const val PAYLOAD_BYTES = 5

        const val GATE_POLL_US = 200u

        val BUDGET = 15.seconds
    }
}
