package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * That a single-buffer flush whose write throws still owes its buffer to the
 * teardown — in **both** flush configurations, because they reach the throw
 * through different callers.
 *
 * `performFlush` takes the entry off the deque before calling the channel, so
 * for the length of that call the buffer is in nobody's hands: not queued for
 * the teardown to release, and not the caller's, since `write` took ownership
 * when it was enqueued.
 *
 * **On this engine the write really does throw.** The POSIX transports reach
 * their socket through a seam that answers with a result type, so the same
 * shape there needs a third-party implementation to fire. Here it is
 * [SocketChannel.write] itself: a reset, a broken pipe or a channel closed
 * underneath answers with an `IOException`, which is how connections
 * ordinarily end. Every one of those cost a pooled buffer for the life of the
 * EventLoop's allocator.
 *
 * **Two cases, because coalescing decides who makes the call.** With it off,
 * `flush()` reaches `performFlush` on the caller. With it on — the shipped
 * default — the flush is a scheduled tick, and a `close()` that lands first
 * runs the drain from inside the teardown. Only the second reaches the
 * teardown's own handling of a throwing drain, and pinning one without the
 * other pins the configuration nobody runs.
 *
 * A closed channel is the deterministic way in. The alternative — a peer that
 * resets while a large write is in flight — reaches the same throw through a
 * race, which is not a thing to assert on.
 */
class NioTransportFlushThrowSeamTest {

    private lateinit var server: ServerSocketChannel
    private lateinit var client: SocketChannel
    private lateinit var accepted: SocketChannel
    private var loop: NioEventLoop? = null

    @BeforeTest
    fun setUp() {
        server = ServerSocketChannel.open()
        server.bind(InetSocketAddress(LOOPBACK, 0))
        client = SocketChannel.open(server.localAddress as InetSocketAddress)
        accepted = server.accept()
        client.configureBlocking(false)
    }

    @AfterTest
    fun tearDown() {
        loop?.close()
        if (client.isOpen) client.close()
        accepted.close()
        server.close()
    }

    @Test
    fun `a flush whose write throws leaves the buffer where the teardown looks`() {
        val outcome = runOnLoop(flushCoalescing = false) { transport, tracker ->
            val buf = tracker.allocate(BUF_CAPACITY).also { it.writerIndex = PAYLOAD_BYTES }
            transport.write(buf)
            client.close()

            val thrown = runCatching { transport.flush() }.exceptionOrNull()
            val afterThrow = tracker.outstandingCount

            transport.close()
            Outcome(thrown, afterThrow, tracker.outstandingCount)
        }

        assertTrue(
            outcome.thrown is ClosedChannelException,
            "the write must have thrown for this test to mean anything, got: ${outcome.thrown}",
        )
        assertEquals(1, outcome.outstandingAfterThrow, "the entry is still owed a release, not released early")
        assertEquals(
            0,
            outcome.outstandingAfterClose,
            "the teardown must find the buffer the failed write let go of",
        )
    }

    @Test
    fun `a teardown whose drain throws still releases what the drain gave back`() {
        // The shipped default: `flush()` only schedules a tick, so the write
        // that throws is the one the teardown itself makes as its first act.
        // Letting that escape skipped every obligation below it -- the release
        // walk among them -- and the teardown claim is spent, so nothing
        // retries.
        val outcome = runOnLoop(flushCoalescing = true) { transport, tracker ->
            val buf = tracker.allocate(BUF_CAPACITY).also { it.writerIndex = PAYLOAD_BYTES }
            transport.write(buf)
            transport.flush()
            client.close()

            val thrown = runCatching { transport.close() }.exceptionOrNull()
            Outcome(thrown, -1, tracker.outstandingCount)
        }

        assertTrue(
            outcome.thrown is ClosedChannelException,
            "the drain's failure is still the caller's, raised once the obligations are met, got: ${outcome.thrown}",
        )
        assertEquals(
            0,
            outcome.outstandingAfterClose,
            "the release walk runs even though the drain before it threw",
        )
    }

    @Test
    fun `a parked waiter is answered by the teardown`() {
        // The other half: here the tick has already run and thrown, so the
        // waiter finds a non-empty queue with nothing scheduled and parks. What
        // would have resumed it -- a drain -- is never coming, because the throw
        // path re-queues without registering write interest. `close()` owes it
        // an answer, and this transport had no stage that gave one.
        val eventLoop = newLoop(flushCoalescing = true)
        val key = runBlocking { eventLoop.registerChannel(client) }
        val tracker = TrackingAllocator()
        val transport = NioIoTransport(client, key, eventLoop, tracker, IdleReadPolicy.DETECT_PEER_CLOSE)

        val armed = CountDownLatch(1)
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val buf = tracker.allocate(BUF_CAPACITY).also { it.writerIndex = PAYLOAD_BYTES }
                transport.write(buf)
                transport.flush()
                client.close()
                armed.countDown()
            },
        )
        if (!armed.await(LOOP_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) fail("setup task did not run")
        // Let the scheduled tick run and throw, so the waiter below parks rather
        // than driving the drain itself.
        val drained = CountDownLatch(1)
        eventLoop.dispatch(EmptyCoroutineContext, Runnable { drained.countDown() })
        if (!drained.await(LOOP_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) fail("the scheduled tick did not run")

        runBlocking {
            // Unconfined so the body runs here up to its own dispatch: when
            // `launch` returns, the registration is already queued on the loop.
            // A barrier behind it is then enough to know it has run -- the loop
            // is FIFO -- which is what makes the close land on a waiter that is
            // genuinely parked, without polling a field the loop owns.
            val waiter = launch(Dispatchers.Unconfined) { runCatching { transport.awaitPendingFlush() } }
            val registered = CountDownLatch(1)
            eventLoop.dispatch(EmptyCoroutineContext, Runnable { registered.countDown() })
            if (!registered.await(LOOP_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) fail("the waiter did not register")

            eventLoop.dispatch(EmptyCoroutineContext, Runnable { transport.close() })
            withTimeout(WAITER_BUDGET_MS) { waiter.join() }
        }
    }

    private class Outcome(
        val thrown: Throwable?,
        val outstandingAfterThrow: Int,
        val outstandingAfterClose: Int,
    )

    /** One loop per test, remembered so [tearDown] closes it. */
    private fun newLoop(flushCoalescing: Boolean): NioEventLoop =
        NioEventLoop(
            name = "nio-flush-throw-test",
            logger = NoopLoggerFactory.logger("nio-flush-throw-test"),
            flushCoalescing = flushCoalescing,
        ).also { loop = it }

    /**
     * Runs [block] on the EventLoop thread with a transport over the connected
     * client channel.
     *
     * On the loop because `close()` runs its teardown inline there; dispatching
     * it from the test thread would leave the release to a task the assertions
     * would race.
     */
    private fun runOnLoop(flushCoalescing: Boolean, block: (NioIoTransport, TrackingAllocator) -> Outcome): Outcome {
        val eventLoop = newLoop(flushCoalescing)
        val key = runBlocking { eventLoop.registerChannel(client) }
        val tracker = TrackingAllocator()
        val transport = NioIoTransport(client, key, eventLoop, tracker, IdleReadPolicy.DETECT_PEER_CLOSE)

        var outcome: Outcome? = null
        val done = CountDownLatch(1)
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                try {
                    outcome = block(transport, tracker)
                } finally {
                    done.countDown()
                }
            },
        )
        if (!done.await(LOOP_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("the dispatched task did not run within timeout")
        }
        return outcome ?: fail("the task threw before producing an outcome")
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** Any capacity above the payload; the flush reads the payload range. */
        const val BUF_CAPACITY = 16

        const val PAYLOAD_BYTES = 5

        const val LOOP_TASK_TIMEOUT_MS = 5_000L

        /** Wall-clock bound on a waiter that must be answered rather than parked. */
        const val WAITER_BUDGET_MS = 5_000L
    }
}
