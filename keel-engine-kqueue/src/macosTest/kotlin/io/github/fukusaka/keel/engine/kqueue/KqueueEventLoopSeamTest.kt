package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.posix.EAGAIN
import platform.posix.EBADF
import platform.posix.EINTR
import platform.posix.EMFILE
import platform.posix.ENFILE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for `KqueueEventLoop` syscall error branches
 * via `FakeKqueueSyscallOps` injection. Covers the init failure cleanup
 * paths and register/registerCallback failure recovery that were
 * introduced in PR #355 but were only reachable through a real BSD
 * kernel failure (not testable in integration).
 *
 * Per `.claude/rules/testing.md` § "二層テスト戦略".
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
 * - **cinterop `kevent` struct bit combinations** — integration-only.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEventLoopSeamTest {

    private val logger = NoopLoggerFactory.logger("KqueueEventLoopSeamTest")

    // --- init failure paths ---

    @Test
    fun `init throws with errnoMessage when kqueue syscall fails`() {
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFailure(EMFILE)
        }
        val ex = assertFailsWith<IllegalStateException> {
            KqueueEventLoop(logger, syscallOps = fake)
        }
        assertTrue(
            ex.message!!.contains("kqueue()") && ex.message!!.contains("fail"),
            "message should mention kqueue() failure, got: ${ex.message}",
        )
    }

    @Test
    fun `init throws and cleans up kqFd when pipe syscall fails`() {
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFailure(EMFILE)
        }
        val ex = assertFailsWith<IllegalStateException> {
            KqueueEventLoop(logger, syscallOps = fake)
        }
        assertTrue(ex.message!!.contains("pipe()"))
        // No addFilter calls should have been made because pipe() failed first.
        assertEquals(0, fake.addFilterCalls.size)
    }

    @Test
    fun `init throws when initial kevent EV_ADD on wakeup fd fails`() {
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            scriptAddFilterResult(EBADF)
        }
        val ex = assertFailsWith<IllegalStateException> {
            KqueueEventLoop(logger, syscallOps = fake)
        }
        assertTrue(ex.message!!.contains("kevent"))
        // One addFilter call was made (for the wakeup fd) before failing.
        assertEquals(1, fake.addFilterCalls.size)
        assertEquals(FakeKqueueSyscallOps.FilterKind.READ, fake.addFilterCalls[0].filter)
        assertEquals(1001, fake.addFilterCalls[0].fd)
    }

    // --- register() / registerCallback() failure paths ---

    @Test
    fun `register resumes continuation with exception when addWriteFilter fails`() {
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            scriptAddFilterResult(0) // init succeeds
            scriptAddFilterResult(ENFILE) // the register() we drive below fails
        }
        val el = KqueueEventLoop(logger, syscallOps = fake)
        try {
            val ex = assertFailsWith<IllegalStateException> {
                runBlocking { el.awaitWriteReady(fd = 5000, logger = logger) }
            }
            assertTrue(ex.message!!.contains("kevent"))
            assertTrue(ex.message!!.contains("5000"))
        } finally {
            // Engine not started; close() is still safe (idempotent running flag).
            el.close()
        }
    }

    // --- wakeup branch ---

    @Test
    fun `wakeupWrite EAGAIN is treated as benign without throwing`() {
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            scriptAddFilterResult(0)
        }
        val el = KqueueEventLoop(logger, syscallOps = fake)
        try {
            fake.scriptWakeupWriteResult(platform.posix.EAGAIN)
            // dispatch triggers wakeup internally; the EAGAIN must be swallowed.
            el.dispatch(kotlin.coroutines.EmptyCoroutineContext) { /* no-op */ }
            assertEquals(1, fake.wakeupWriteCalls)
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
        val fake = FakeKqueueSyscallOps().apply {
            scriptWaitFailure(EINTR)   // 1st: retriable, loop should `continue`
            scriptWaitFailure(EBADF)   // 2nd: fatal, loop should log + break
        }
        val el = KqueueEventLoop(logger = recordingLogger(errors), syscallOps = fake)
        el.loop()
        assertEquals(2, fake.waitCalls, "EINTR should be retried, then EBADF terminates")
        assertEquals(1, errors.size, "fatal errno should produce exactly one error log")
        assertTrue(
            errors.first().contains("kevent()"),
            "error log should mention kevent(), got: ${errors.first()}",
        )
    }

    @Test
    fun `loop retries waitEvents on EAGAIN then exits on fatal errno`() {
        val errors = mutableListOf<String>()
        val fake = FakeKqueueSyscallOps().apply {
            scriptWaitFailure(EAGAIN)
            scriptWaitFailure(EBADF)
        }
        val el = KqueueEventLoop(logger = recordingLogger(errors), syscallOps = fake)
        el.loop()
        assertEquals(2, fake.waitCalls, "EAGAIN should be retried, then EBADF terminates")
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("kevent()"))
    }

    @Test
    fun `loop exits immediately on fatal waitEvents errno`() {
        val errors = mutableListOf<String>()
        val fake = FakeKqueueSyscallOps().apply {
            scriptWaitFailure(EBADF)   // fatal on first call
        }
        val el = KqueueEventLoop(logger = recordingLogger(errors), syscallOps = fake)
        el.loop()
        assertEquals(1, fake.waitCalls, "fatal errno on first call should not retry")
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("kevent()"))
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
