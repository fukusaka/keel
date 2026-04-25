package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.EAGAIN
import platform.posix.EBADF
import platform.posix.EMFILE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for `EpollEventLoop` syscall error branches
 * via `FakeEpollSyscallOps` injection. Covers the init failure cleanup
 * paths and `addOrModifyEpoll` / `removeInterestFromEpoll` error logging
 * branches that were introduced in PR #355 but were only reachable
 * through a real Linux kernel failure (not testable in integration).
 *
 * Counterpart of `KqueueEventLoopSeamTest` on Linux.
 * Per the two-layer test strategy.
 *
 * ## What this file does NOT cover
 *
 * - **pthread_create / pthread_join** — not part of the seam (the
 *   lifecycle is exercised by every integration test that starts the
 *   engine; seam injection would add cinterop-heavy scaffolding for
 *   marginal value).
 * - **main loop `waitEvents` retry / fatal exit** — deferred to a
 *   separate test that drives `loop()` directly on the test thread.
 * - **`epoll_event` struct bit combinations** — integration-only.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollEventLoopSeamTest {

    private val logger = NoopLoggerFactory.logger("EpollEventLoopSeamTest")

    // --- init failure paths ---

    @Test
    fun `init throws with errnoMessage when epoll_create1 fails`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFailure(EMFILE)
        }
        val ex = assertFailsWith<IllegalStateException> {
            EpollEventLoop(logger, syscallOps = fake)
        }
        assertTrue(
            ex.message!!.contains("epoll_create1()"),
            "message should mention epoll_create1() failure, got: ${ex.message}",
        )
    }

    @Test
    fun `init throws and cleans up epFd when eventfd fails`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFailure(EMFILE)
        }
        val ex = assertFailsWith<IllegalStateException> {
            EpollEventLoop(logger, syscallOps = fake)
        }
        assertTrue(ex.message!!.contains("eventfd()"))
        // No epoll_ctl calls should have been made because eventfd() failed first.
        assertEquals(0, fake.ctlCalls.size)
    }

    @Test
    fun `init throws when initial epoll_ctl ADD on wakeupFd fails`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(EBADF)
        }
        val ex = assertFailsWith<IllegalStateException> {
            EpollEventLoop(logger, syscallOps = fake)
        }
        assertTrue(ex.message!!.contains("epoll_ctl"))
        // One ADD call was made (for the wakeup fd) before failing.
        assertEquals(1, fake.ctlCalls.size)
        assertEquals(FakeEpollSyscallOps.CtlOp.ADD, fake.ctlCalls[0].op)
        assertEquals(1001, fake.ctlCalls[0].fd)
    }

    // --- wakeup branch ---

    @Test
    fun `wakeup write EAGAIN is treated as benign without throwing`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0)
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        try {
            fake.scriptWakeupWriteResult(EAGAIN)
            // dispatch triggers wakeup internally; EAGAIN must be swallowed.
            el.dispatch(kotlin.coroutines.EmptyCoroutineContext) { /* no-op */ }
            assertEquals(1, fake.wakeupWriteCalls)
        } finally {
            el.close()
        }
    }

    // --- addOrModifyEpoll EEXIST fallback path ---

    @Test
    fun `addOrModifyEpoll falls back to MOD on EEXIST`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD succeeds
            // The next ADD returns EEXIST so MOD fallback kicks in.
            scriptAddResult(platform.posix.EEXIST)
            scriptModResult(0)
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        try {
            // Trigger addOrModifyEpoll via registerCallback (fd 2000, READ).
            el.registerCallback(fd = 2000, interest = EpollEventLoop.Interest.READ) { _ -> /* noop */ }
            val ctl = fake.ctlCalls
            // init ADD (wakeup, 1001), then ADD (2000) -> EEXIST, then MOD (2000).
            assertEquals(3, ctl.size)
            assertEquals(FakeEpollSyscallOps.CtlOp.ADD, ctl[1].op)
            assertEquals(2000, ctl[1].fd)
            assertEquals(FakeEpollSyscallOps.CtlOp.MOD, ctl[2].op)
            assertEquals(2000, ctl[2].fd)
        } finally {
            el.close()
        }
    }
}
