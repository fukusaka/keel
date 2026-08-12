package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.StandardSocketOptions
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
 * What a flush that throws still owes: its buffer to the teardown, and — once
 * the teardown is the only thing that can answer it — the caller parked on that
 * flush.
 *
 * The buffer in **both** flush configurations, because they reach the throw
 * through different callers; the parked caller under the shipped default, which
 * is where the teardown runs the drain itself.
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
 * **Coalescing decides who makes the call.** With it off,
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
    fun `a scheduled drain that throws ends the connection`() {
        // The tick is the drain with nobody to tell. Left to the loop's task
        // drain, its throw leaves a transport that is open, holds an entry
        // nothing will send, and parks the next caller for good. Measured that
        // way against a peer reset -- linger-0 close, so RST rather than FIN --
        // `awaitPendingFlush` never returned: the write throws, and the read
        // side throws the same way, so nothing else ends the connection either.
        // A channel closed underneath reaches the same throw without waiting on
        // when the reset lands, which is what this asserts on.
        val eventLoop = newLoop(flushCoalescing = true)
        val key = runBlocking { eventLoop.registerChannel(client) }
        val tracker = TrackingAllocator()
        val transport = NioIoTransport(client, key, eventLoop, tracker, IdleReadPolicy.DETECT_PEER_CLOSE)
        val inactive = CountDownLatch(1)
        transport.onReadClosed = { inactive.countDown() }

        runOnLoopAndWait(eventLoop) {
            val buf = tracker.allocate(BUF_CAPACITY).also { it.writerIndex = PAYLOAD_BYTES }
            transport.write(buf)
            transport.flush()
            client.close()
        }
        runOnLoopAndWait(eventLoop) { } // barrier: the scheduled tick has run and thrown

        assertTrue(
            inactive.await(LOOP_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS),
            "the connection must be reported inactive, not left open with nobody told",
        )
        runBlocking {
            val answer = CompletableDeferred<Throwable?>()
            launch(Dispatchers.Unconfined) {
                answer.complete(runCatching { transport.awaitPendingFlush() }.exceptionOrNull())
            }
            val cause = withTimeoutOrNull(WAITER_BUDGET_MS) { answer.await() }
                ?: fail("the caller parked: nothing ended the connection the failed drain left behind")
            assertTrue(
                cause is CancellationException,
                "and is told the flush did not drain rather than that it did, got: $cause",
            )
        }
        assertEquals(0, tracker.outstandingCount, "the entry the failed drain re-queued is released by that same close")
    }

    @Test
    fun `a parked waiter is answered by the teardown`() {
        // The teardown's own waiter stage, reached the way production does: a
        // full send buffer stalls the drain, so the tick returns having sent
        // nothing and cleared `flushScheduled`. The caller then finds a
        // non-empty queue with no tick to run eagerly, and parks on a drain
        // only write readiness could finish -- which never comes, because the
        // peer is not reading. `close()` owes it an answer, and this transport
        // had no stage that gave one.
        client.setOption(StandardSocketOptions.SO_SNDBUF, STALL_SNDBUF_BYTES)
        val eventLoop = newLoop(flushCoalescing = true)
        val key = runBlocking { eventLoop.registerChannel(client) }
        val tracker = TrackingAllocator()
        val transport = NioIoTransport(client, key, eventLoop, tracker, IdleReadPolicy.DETECT_PEER_CLOSE)

        runOnLoopAndWait(eventLoop) {
            val buf = tracker.allocate(STALL_PAYLOAD_BYTES).also { it.writerIndex = STALL_PAYLOAD_BYTES }
            transport.write(buf)
            transport.flush()
        }
        runOnLoopAndWait(eventLoop) { } // barrier: the tick has run and stalled

        runBlocking {
            // Unconfined so the body runs here up to its own dispatch: when
            // `launch` returns, the registration is already queued on the loop.
            // A barrier behind it is then enough to know it has run -- the loop
            // is FIFO -- which is what makes the close land on a waiter that is
            // genuinely parked, without polling a field the loop owns.
            val answer = CompletableDeferred<Throwable?>()
            val waiter = launch(Dispatchers.Unconfined) {
                answer.complete(runCatching { transport.awaitPendingFlush() }.exceptionOrNull())
            }
            runOnLoopAndWait(eventLoop) { } // barrier: the waiter has registered
            assertTrue(waiter.isActive, "the waiter must be parked for the teardown to be what answers it")

            eventLoop.dispatch(EmptyCoroutineContext, Runnable { transport.close() })
            withTimeout(WAITER_BUDGET_MS) { waiter.join() }

            // *How* it is answered, not just that it was: told the flush
            // succeeded, a caller would go on believing bytes it never sent
            // reached the peer. `resume(Unit)` in place of the cancel keeps a
            // join-only assertion green.
            val cause = answer.await()
            assertTrue(
                cause is CancellationException,
                "the waiter must be told the flush did not drain, got: $cause",
            )
            assertTrue(
                cause.message?.contains("could drain") == true,
                "and told why, rather than cancelled bare: ${cause.message}",
            )
            assertEquals(
                0,
                tracker.outstandingCount,
                "and the entry the stalled drain left queued is released by the same teardown",
            )
        }
    }

    /** Runs [body] on [eventLoop] and returns once it has run; also usable as a barrier. */
    private fun runOnLoopAndWait(eventLoop: NioEventLoop, body: () -> Unit) {
        val done = CountDownLatch(1)
        eventLoop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                try {
                    body()
                } finally {
                    done.countDown()
                }
            },
        )
        if (!done.await(LOOP_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) fail("the dispatched task did not run")
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

        /** Small enough that one payload cannot leave the send buffer while the peer never reads. */
        const val STALL_SNDBUF_BYTES = 2048

        /** Far above [STALL_SNDBUF_BYTES], so the write stalls rather than completing. */
        const val STALL_PAYLOAD_BYTES = 256 * 1024
    }
}
