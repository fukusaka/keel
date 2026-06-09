package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EPERM
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [IoUringEventLoop]'s ring lifecycle via
 * [FakeIoUringRing] injection. Covers the `io_uring_queue_init` failure
 * branch — only reachable under real kernel restriction (`EPERM` when
 * `io_uring` is disabled, `ENOMEM` under pressure) — and verifies the
 * `IORING_SETUP_*` flag assembly and `queue_exit` teardown gating.
 *
 * Part of the io_uring native API seam effort. [IoUringEventLoop.initRing]
 * issues no thread-affinity assertion, so the tests drive it directly on
 * the test thread without spawning the EventLoop pthread — no timeout
 * needed (no async / dispatch / I/O).
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringRingLifecycleSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringRingLifecycleSeamTest")

    /**
     * Builds an [IoUringEventLoop] backed by [ring] (and a fake
     * `syscallOps`), runs [block], then tears the loop down.
     */
    private fun withEventLoop(
        ring: FakeIoUringRing,
        capabilities: IoUringCapabilities = IoUringCapabilities(),
        ringSize: Int = IoUringEventLoop.DEFAULT_RING_SIZE,
        block: (IoUringEventLoop) -> Unit,
    ) {
        val el = IoUringEventLoop(
            logger,
            capabilities = capabilities,
            ringSize = ringSize,
            syscallOps = FakeIoUringSyscallOps(),
            ioUringRing = ring,
        )
        try {
            block(el)
        } finally {
            el.close()
            ring.dispose()
        }
    }

    // --- initRing ---

    @Test
    fun `initRing throws with the flag word when queueInit fails`() {
        val fake = FakeIoUringRing().apply { scriptQueueInitFailure(EPERM) }
        withEventLoop(fake) { el ->
            val ex = assertFailsWith<IllegalStateException> { el.initRing() }
            assertTrue(
                ex.message!!.contains("io_uring_queue_init() failed"),
                "message should mention queue_init failure, got: ${ex.message}",
            )
        }
    }

    @Test
    fun `initRing folds the enabled capabilities into the setup flags`() {
        val caps = IoUringCapabilities(coopTaskrun = false, singleIssuer = true, deferTaskrun = true)
        val fake = FakeIoUringRing()
        withEventLoop(fake, capabilities = caps) { el ->
            el.initRing()
            assertEquals(
                FakeIoUringRing.SetupFlagsArgs(coopTaskrun = false, singleIssuer = true, deferTaskrun = true),
                fake.lastSetupFlagsArgs,
            )
            // The flag word produced by setupFlags must be the one handed to
            // queueInit — guards the initRing wiring, not just the assembly.
            assertEquals(
                FakeIoUringRing.SINGLE_FLAG or FakeIoUringRing.DEFER_FLAG,
                fake.lastQueueInitFlags,
            )
        }
    }

    @Test
    fun `initRing passes the configured ring size to queueInit`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake, ringSize = 16) { el ->
            el.initRing()
            assertEquals(16, fake.lastQueueInitEntries)
        }
    }

    // --- close teardown gating ---

    @Test
    fun `close after a successful initRing tears the ring down`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            el.initRing()
            el.close()
            assertEquals(1, fake.queueExitCalls)
        }
    }

    @Test
    fun `close without initRing does not tear the ring down`() {
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            el.close()
            // ringInitialized is false — queue_exit on a zero-initialised ring
            // would close fd 0 (stdin).
            assertEquals(0, fake.queueExitCalls)
        }
    }

    @Test
    fun `close after a failed initRing does not tear the ring down`() {
        val fake = FakeIoUringRing().apply { scriptQueueInitFailure(EPERM) }
        withEventLoop(fake) { el ->
            assertFailsWith<IllegalStateException> { el.initRing() }
            el.close()
            assertEquals(0, fake.queueExitCalls, "a failed queue_init must not arm queue_exit")
        }
    }

    @Test
    fun `close is idempotent — a second call does not invoke queue_exit again`() {
        // The teardown is gated on `running.compareAndSet(1, 0)`; subsequent
        // calls return early. Pin it so a future change to the close path
        // (e.g. moving wakeupFd cleanup outside the CAS guard) cannot make
        // teardown double-fire — that would double-close wakeupFd and
        // potentially queue_exit a destroyed ring. The `withEventLoop`
        // helper itself calls `close()` in its finally block, so this test
        // explicitly exercises one in-block close + the implicit finally
        // close and asserts both together count as one teardown.
        val fake = FakeIoUringRing()
        withEventLoop(fake) { el ->
            el.initRing()
            el.close()
            assertEquals(1, fake.queueExitCalls, "first close issues queue_exit")
            el.close()
            assertEquals(1, fake.queueExitCalls, "second close is a no-op")
        }
        // After the implicit finally close() the count must still be 1.
        assertEquals(1, fake.queueExitCalls, "finally close is also a no-op")
    }
}
