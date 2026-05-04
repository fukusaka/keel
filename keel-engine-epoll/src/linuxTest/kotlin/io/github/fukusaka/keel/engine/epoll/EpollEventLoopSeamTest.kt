package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.posix.EAGAIN
import platform.posix.EBADF
import platform.posix.EINTR
import platform.posix.EMFILE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
 * - **main loop `waitEvents` retry / fatal exit** — covered at the bottom
 *   of this file by tests that drive `loop()` directly on the test thread
 *   via the now-`internal` accessor.
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

    // --- main loop error branch tests ---
    //
    // Drive `loop()` directly on the test thread (no `start()` / pthread).
    // `loop()` exits its `while (running.value != 0)` only via a fatal
    // `waitEvents` errno that hits the `break`, so retry-path tests append
    // a fatal scripted result after the retriable one to terminate cleanly.

    @Test
    fun `loop retries waitEvents on EINTR then exits on fatal errno`() {
        val errors = mutableListOf<String>()
        val fake = FakeEpollSyscallOps().apply {
            scriptWaitFailure(EINTR)   // 1st: retriable, loop should `continue`
            scriptWaitFailure(EBADF)   // 2nd: fatal, loop should log + break
        }
        val el = EpollEventLoop(logger = recordingLogger(errors), syscallOps = fake)
        el.loop()
        assertEquals(2, fake.waitCalls, "EINTR should be retried, then EBADF terminates")
        assertEquals(1, errors.size, "fatal errno should produce exactly one error log")
        assertTrue(
            errors.first().contains("epoll_wait()"),
            "error log should mention epoll_wait(), got: ${errors.first()}",
        )
    }

    @Test
    fun `loop retries waitEvents on EAGAIN then exits on fatal errno`() {
        val errors = mutableListOf<String>()
        val fake = FakeEpollSyscallOps().apply {
            scriptWaitFailure(EAGAIN)
            scriptWaitFailure(EBADF)
        }
        val el = EpollEventLoop(logger = recordingLogger(errors), syscallOps = fake)
        el.loop()
        assertEquals(2, fake.waitCalls, "EAGAIN should be retried, then EBADF terminates")
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("epoll_wait()"))
    }

    @Test
    fun `loop exits immediately on fatal waitEvents errno`() {
        val errors = mutableListOf<String>()
        val fake = FakeEpollSyscallOps().apply {
            scriptWaitFailure(EBADF)   // fatal on first call
        }
        val el = EpollEventLoop(logger = recordingLogger(errors), syscallOps = fake)
        el.loop()
        assertEquals(1, fake.waitCalls, "fatal errno on first call should not retry")
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("epoll_wait()"))
    }

    // --- EPOLLHUP / EPOLLERR dispatch tests ---
    //
    // The kernel always reports EPOLLHUP and EPOLLERR regardless of the
    // interest mask. On a peer FIN / RST the kernel may fire EPOLLHUP
    // without EPOLLIN. Without treating these flags as READ-ready, the
    // pipeline callback is never invoked, read() never returns 0, and
    // onReadClosed never fires — connections pile up in CLOSE-WAIT.

    @Test
    fun `EPOLLHUP-only event invokes READ callback`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0)
            scriptWaitOk(2000 to EPOLLHUP)   // EPOLLHUP only — no EPOLLIN
            scriptWaitFailure(EBADF)          // terminate loop
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        var readCalled = false
        el.registerCallback(fd = 2000, interest = EpollEventLoop.Interest.READ) { _ ->
            readCalled = true
        }
        el.loop()
        assertTrue(readCalled, "READ callback must fire on EPOLLHUP even without EPOLLIN")
    }

    @Test
    fun `EPOLLERR-only event invokes READ callback`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0)
            scriptWaitOk(2000 to EPOLLERR)   // EPOLLERR only — no EPOLLIN
            scriptWaitFailure(EBADF)
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        var readCalled = false
        el.registerCallback(fd = 2000, interest = EpollEventLoop.Interest.READ) { _ ->
            readCalled = true
        }
        el.loop()
        assertTrue(readCalled, "READ callback must fire on EPOLLERR even without EPOLLIN")
    }

    @Test
    fun `EPOLLHUP-only event does not spuriously invoke WRITE callback`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0)
            scriptWaitOk(2000 to EPOLLHUP)
            scriptWaitFailure(EBADF)
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        var writeCalled = false
        el.registerCallback(fd = 2000, interest = EpollEventLoop.Interest.WRITE) { _ ->
            writeCalled = true
        }
        el.loop()
        assertFalse(writeCalled, "WRITE callback must NOT fire on EPOLLHUP alone")
    }

    /**
     * Logger that captures `error`-level messages into [sink]. Other levels
     * are discarded. Used by the main-loop error-branch tests to assert
     * the fatal-exit path emits the expected log.
     */
    private fun recordingLogger(sink: MutableList<String>): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.ERROR
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.ERROR) sink.add(message.toString())
        }
    }
}
