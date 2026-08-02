package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import java.nio.channels.Pipe
import java.nio.channels.SelectionKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pin the EventLoop-thread funnel introduced for the I/O ownership
 * invariant. [NioEventLoop.setInterestCallback] /
 * [NioEventLoop.removeInterest] mutate [NioEventLoop.KeyCallbacks] and
 * [SelectionKey.interestOps] without locking — both must run on the
 * EventLoop thread that owns the [java.nio.channels.Selector].
 *
 * The public entry points route cross-thread callers through
 * [NioEventLoop.dispatch] (which queues a [Runnable] on the EL thread
 * and wakes the selector). Callers that are already on the EL thread
 * skip the dispatch and apply the mutation inline. These tests pin
 * both branches:
 *
 * - Cross-thread: `interestOps` is **not** mutated synchronously when
 *   called off-loop; the change appears only after the EL thread drains
 *   the task queue.
 * - Inline: when invoked from a `dispatch`-ed task (= on the EL
 *   thread), the mutation is visible before the dispatched block
 *   returns.
 */
class NioEventLoopFunnelTest {

    private lateinit var loop: NioEventLoop
    private lateinit var pipe: Pipe
    private lateinit var key: SelectionKey

    @BeforeTest
    fun setUp() {
        loop = NioEventLoop("nio-funnel-test", NoopLoggerFactory.logger("nio-funnel-test"))
        pipe = Pipe.open()
        pipe.source().configureBlocking(false)
        // Register the source end with the loop (interestOps=0) so we can
        // observe interest-mask mutations.
        key = kotlinx.coroutines.runBlocking { loop.registerChannel(pipe.source()) }
        assertEquals(0, key.interestOps(), "registration starts with no interest")
    }

    @AfterTest
    fun tearDown() {
        loop.close()
        pipe.source().close()
        pipe.sink().close()
    }

    // --- EventLoop-thread funnel pin ---

    @Test
    fun `setInterestCallback inside dispatch applies inline before block returns`() {
        val applied = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                // Inside a dispatched task we are on the EL thread; the funnel
                // takes the inline branch. The interest mask must be visible
                // immediately, before this Runnable returns.
                loop.setInterestCallback(key, SelectionKey.OP_READ, Runnable { /* no-op */ })
                applied.set((key.interestOps() and SelectionKey.OP_READ) != 0)
                latch.countDown()
            },
        )
        if (!latch.await(IO_OP_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("dispatched task did not run within timeout")
        }
        assertTrue(applied.get(), "OP_READ should be set inline when invoked on the EL thread")
    }

    @Test
    fun `removeInterest inside dispatch applies inline before block returns`() {
        // Arrange: arm OP_READ first.
        val armLatch = CountDownLatch(1)
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                loop.setInterestCallback(key, SelectionKey.OP_READ, Runnable { /* no-op */ })
                armLatch.countDown()
            },
        )
        if (!armLatch.await(IO_OP_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("arm dispatched task did not run within timeout")
        }
        assertEquals(SelectionKey.OP_READ, key.interestOps())

        // Act + Assert: remove from inside a dispatched task.
        val removed = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                loop.removeInterest(key, SelectionKey.OP_READ)
                removed.set((key.interestOps() and SelectionKey.OP_READ) == 0)
                latch.countDown()
            },
        )
        if (!latch.await(IO_OP_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("remove dispatched task did not run within timeout")
        }
        assertTrue(removed.get(), "OP_READ should be cleared inline when invoked on the EL thread")
    }

    @Test
    fun `setInterestCallback from external thread is funnelled via dispatch and eventually applied`() {
        // Call from the test (main) thread — not the EL thread. The funnel
        // routes the work through [dispatch], so the mutation is NOT
        // guaranteed to be visible synchronously; it becomes visible after
        // the EL thread drains the task queue.
        loop.setInterestCallback(key, SelectionKey.OP_READ, Runnable { /* no-op */ })

        // Wait for the EL to drain via a synchronous round trip: enqueue
        // a barrier Runnable behind the funnelled work and wait for it.
        val barrier = CountDownLatch(1)
        loop.dispatch(EmptyCoroutineContext, Runnable { barrier.countDown() })
        if (!barrier.await(IO_OP_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("EL thread did not drain barrier within timeout")
        }
        assertEquals(SelectionKey.OP_READ, key.interestOps() and SelectionKey.OP_READ)
    }
}
