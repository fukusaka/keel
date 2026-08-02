package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.channels.Pipe
import java.nio.channels.SelectionKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Red-Green regression test for `NioEventLoop.processReadyKey`'s
 * stale-interest safety net (added by PR #465). The branch runs when the
 * [java.nio.channels.Selector] reports a direction ready but the matching
 * per-op slot in [NioEventLoop.KeyCallbacks] holds `null`. Under the
 * happy-path invariant this is unreachable — every code path that arms
 * an interest bit also installs the callback — so triggering it in a
 * pure-happy-path test is impossible.
 *
 * The seam this test uses is fault-injection at the KeyCallbacks slot:
 * arm the interest bit normally, then null the per-op field on the EL
 * thread before the ready event fires. That leaves the key in a state
 * only reachable if a future change breaks the invariant. The WARN
 * emission + interest-clear that this test pins guarantee the loop does
 * not spin on the fd afterwards (the point of the safety net).
 *
 * JVM-only. The Native engines' equivalent branches
 * (`EpollEventLoop` / `KqueueEventLoop`, PR #447 / #449) already have
 * their own seam tests that use `FakeSyscallOps`; on JVM the fault is
 * injected against a real `Pipe` + real `Selector`, since the callbacks
 * hang off the `SelectionKey` attachment directly.
 */
class NioStaleInterestSeamTest {

    private lateinit var warns: MutableList<String>
    private lateinit var loop: NioEventLoop
    private lateinit var pipe: Pipe
    private lateinit var key: SelectionKey

    @BeforeTest
    fun setUp() {
        warns = mutableListOf()
        loop = NioEventLoop("nio-stale-interest-test", recordingWarnLogger(warns))
        pipe = Pipe.open()
        pipe.source().configureBlocking(false)
        key = runBlocking { loop.registerChannel(pipe.source()) }
    }

    @AfterTest
    fun tearDown() {
        loop.close()
        pipe.source().close()
        pipe.sink().close()
    }

    @Test
    fun `stale-interest branch fires WARN and clears interest when readCallback is nulled`() {
        // Arrange: arm OP_READ with a real callback so the invariant holds
        // at this point. If the callback ever ran, we would know the
        // fault-injection setup missed its window.
        val callbackRan = AtomicInteger(0)
        val armLatch = CountDownLatch(1)
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                loop.setInterestCallback(key, SelectionKey.OP_READ, Runnable { callbackRan.incrementAndGet() })
                armLatch.countDown()
            },
        )
        if (!armLatch.await(IO_OP_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("arm dispatched task did not run within timeout")
        }
        assertEquals(SelectionKey.OP_READ, key.interestOps() and SelectionKey.OP_READ)

        // Inject the fault on the EL thread so the callback field write is
        // ordered before the loop dispatches processReadyKey. The
        // interest bit is left set, mimicking a hypothetical bug where a
        // slot is cleared without also clearing its interest.
        val faultLatch = CountDownLatch(1)
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                val callbacks = key.attachment() as NioEventLoop.KeyCallbacks
                callbacks.readCallback = null
                faultLatch.countDown()
            },
        )
        if (!faultLatch.await(IO_OP_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("fault-injection dispatched task did not run within timeout")
        }

        // Act: make OP_READ ready by writing to the paired pipe sink. The
        // Selector picks it up on its next select() cycle and calls
        // processReadyKey, which observes a null readCallback with the
        // OP_READ ready bit set — the safety-net branch.
        pipe.sink().write(ByteBuffer.wrap(byteArrayOf(0x42)))

        // Assert (safety net fired): the WARN is emitted and OP_READ is
        // cleared to prevent a select-spin.
        val warnDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IO_OP_TIMEOUT_MS)
        while (warns.isEmpty() && System.nanoTime() < warnDeadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        assertTrue(warns.isNotEmpty(), "expected a WARN for the stale OP_READ interest")
        assertTrue(
            warns.any { it.contains("no handler") && it.contains("OP_READ") },
            "WARN should mention 'no handler' and OP_READ, got: $warns",
        )

        // Verify the interest was cleared to prevent a spin. Perform the
        // read on the EL thread since interestOps is unsynchronised.
        val interestAfter = AtomicInteger(-1)
        val readbackLatch = CountDownLatch(1)
        loop.dispatch(
            EmptyCoroutineContext,
            Runnable {
                interestAfter.set(key.interestOps())
                readbackLatch.countDown()
            },
        )
        if (!readbackLatch.await(IO_OP_SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            fail("readback dispatched task did not run within timeout")
        }
        assertEquals(
            0,
            interestAfter.get() and SelectionKey.OP_READ,
            "OP_READ interest must be cleared after the safety net fires",
        )

        // And the callback itself never ran (there was none).
        assertEquals(0, callbackRan.get(), "no callback should have fired")
    }

    private fun recordingWarnLogger(sink: MutableList<String>): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.WARN
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.WARN) sink.add(message.toString())
        }
    }

    private companion object {
        private const val POLL_INTERVAL_MS = 25L
    }
}
