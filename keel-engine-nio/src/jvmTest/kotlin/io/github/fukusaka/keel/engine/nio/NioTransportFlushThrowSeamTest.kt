package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.nio.channels.ClosedChannelException
import java.nio.channels.SelectionKey
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
 * teardown.
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
 * process.
 *
 * A closed channel is the deterministic way in. The alternative — a peer that
 * resets while a large write is in flight — reaches the same throw through a
 * race, which is not a thing to assert on.
 */
class NioTransportFlushThrowSeamTest {

    private lateinit var loop: NioEventLoop
    private lateinit var server: ServerSocketChannel
    private lateinit var client: SocketChannel
    private lateinit var accepted: SocketChannel
    private lateinit var key: SelectionKey

    @BeforeTest
    fun setUp() {
        // Coalescing off so `flush()` reaches `performFlush` on the calling
        // thread rather than through a task this test would then have to chase.
        loop = NioEventLoop(
            name = "nio-flush-throw-test",
            logger = NoopLoggerFactory.logger("nio-flush-throw-test"),
            flushCoalescing = false,
        )
        server = ServerSocketChannel.open()
        server.bind(InetSocketAddress(LOOPBACK, 0))
        client = SocketChannel.open(server.localAddress as InetSocketAddress)
        accepted = server.accept()
        client.configureBlocking(false)
        key = runBlocking { loop.registerChannel(client) }
    }

    @AfterTest
    fun tearDown() {
        loop.close()
        if (client.isOpen) client.close()
        accepted.close()
        server.close()
    }

    @Test
    fun `a flush whose write throws leaves the buffer where the teardown looks`() {
        val tracker = TrackingAllocator()
        val transport = NioIoTransport(client, key, loop, tracker, IdleReadPolicy.DETECT_PEER_CLOSE)

        // Everything on the loop thread: `close()` runs its teardown inline
        // there, and dispatching it from here would leave the release to a task
        // this assertion would race.
        var thrown: Throwable? = null
        var outstandingAfterThrow = -1
        var outstandingAfterClose = -1
        val done = CountDownLatch(1)
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                try {
                    val buf = tracker.allocate(BUF_CAPACITY).also { it.writerIndex = PAYLOAD_BYTES }
                    transport.write(buf)
                    client.close()

                    thrown = runCatching { transport.flush() }.exceptionOrNull()
                    outstandingAfterThrow = tracker.outstandingCount

                    transport.close()
                    outstandingAfterClose = tracker.outstandingCount
                } finally {
                    done.countDown()
                }
            },
        )
        if (!done.await(LOOP_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("the dispatched task did not run within timeout")
        }

        assertTrue(
            thrown is ClosedChannelException,
            "the write must have thrown for this test to mean anything, got: $thrown",
        )
        assertEquals(1, outstandingAfterThrow, "the entry is still owed a release, not released early")
        assertEquals(0, outstandingAfterClose, "the teardown must find the buffer the failed write let go of")
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"

        /** Any capacity above the payload; the flush reads the payload range. */
        const val BUF_CAPACITY = 16

        const val PAYLOAD_BYTES = 5

        const val LOOP_TASK_TIMEOUT_MS = 5_000L
    }
}
