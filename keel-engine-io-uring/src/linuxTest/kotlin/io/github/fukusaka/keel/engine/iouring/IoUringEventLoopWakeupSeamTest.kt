package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Runnable
import platform.posix.EAGAIN
import platform.posix.EBADF
import platform.posix.EMFILE
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [IoUringEventLoop]'s `eventfd(2)` wakeup
 * branches via [FakeIoUringSyscallOps] injection. Covers the init-time
 * `eventfd(2)` failure path and the [IoUringEventLoop.dispatch]-driven
 * `eventfd_write(2)` error branches that are only reachable under
 * kernel pressure (EMFILE / EBADF) or counter saturation (EAGAIN), and
 * therefore not testable in integration without fault injection.
 *
 * Counterpart of `EpollEventLoopSeamTest` on the io_uring side. Per the
 * two-layer test strategy in `.claude/rules/testing.md`.
 *
 * ## What this file does NOT cover
 *
 * - **`io_uring_*` SQE / CQE machinery** — outside the
 *   [IoUringSyscallOps] seam (intentional: faking kernel CQE delivery
 *   would require emulating io_uring semantics, not just per-syscall
 *   outcomes). Covered end-to-end by `IoUringPipelinedServerTest`.
 * - **`pthread_create` / `pthread_join`** — not part of the seam (the
 *   lifecycle is exercised by every integration test that starts the
 *   engine).
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringEventLoopWakeupSeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringEventLoopWakeupSeamTest")

    // --- init failure path ---

    @Test
    fun `init throws with errnoMessage when eventfd_create fails with EMFILE`() {
        val fake = FakeIoUringSyscallOps().apply {
            scriptEventfdCreateFailure(EMFILE)
        }
        val ex = assertFailsWith<IllegalStateException> {
            IoUringEventLoop(logger, syscallOps = fake)
        }
        assertTrue(
            ex.message!!.contains("eventfd()"),
            "message should mention eventfd() failure, got: ${ex.message}",
        )
        assertEquals(1, fake.eventfdCreateCalls)
    }

    // --- dispatch wakeup branches ---

    @Test
    fun `dispatch triggers eventfd_write once when called from a non-EventLoop thread`() {
        val fake = FakeIoUringSyscallOps().apply {
            scriptEventfdCreateFd(fd = 3000)
        }
        // msgRingWakeup=false forces the eventfd fallback path; the loop is
        // not started so eventLoopThread == null and dispatch() skips the
        // inEventLoop() early-return.
        val el = IoUringEventLoop(
            logger,
            capabilities = IoUringCapabilities(msgRingWakeup = false),
            syscallOps = fake,
        )
        try {
            el.dispatch(EmptyCoroutineContext, Runnable { /* no-op */ })
            assertEquals(1, fake.eventfdWakeupWriteCalls)
            assertEquals(listOf(3000), fake.eventfdWakeupWriteArgs)
        } finally {
            el.close()
        }
    }

    @Test
    fun `wakeup write EAGAIN is treated as benign without throwing`() {
        val fake = FakeIoUringSyscallOps().apply {
            scriptEventfdCreateFd(fd = 3001)
            scriptEventfdWakeupWriteResult(EAGAIN)
        }
        val el = IoUringEventLoop(
            logger,
            capabilities = IoUringCapabilities(msgRingWakeup = false),
            syscallOps = fake,
        )
        try {
            // dispatch() routes through wakeup() → eventfdWakeupWrite. EAGAIN
            // means the eventfd counter is saturated and a wakeup is already
            // pending; the branch must be swallowed at debug level without
            // bubbling an exception.
            el.dispatch(EmptyCoroutineContext, Runnable { /* no-op */ })
            assertEquals(1, fake.eventfdWakeupWriteCalls)
        } finally {
            el.close()
        }
    }

    @Test
    fun `wakeup write non-EAGAIN errno is logged but does not throw`() {
        val fake = FakeIoUringSyscallOps().apply {
            scriptEventfdCreateFd(fd = 3002)
            scriptEventfdWakeupWriteResult(EBADF)
        }
        val el = IoUringEventLoop(
            logger,
            capabilities = IoUringCapabilities(msgRingWakeup = false),
            syscallOps = fake,
        )
        try {
            // EBADF indicates a corrupted wakeupFd — programming error, but
            // the wakeup path must not throw (the loop continues; the caller
            // observes the failure via a warn-level log).
            el.dispatch(EmptyCoroutineContext, Runnable { /* no-op */ })
            assertEquals(1, fake.eventfdWakeupWriteCalls)
        } finally {
            el.close()
        }
    }
}
