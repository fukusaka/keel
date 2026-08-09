package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.darwin.EVFILT_WRITE
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EBADF
import platform.posix.EINTR
import platform.posix.EMFILE
import platform.posix.ENFILE
import platform.posix.ENOMEM
import platform.posix.F_GETFD
import platform.posix.SOCK_STREAM
import platform.posix.fcntl
import platform.posix.socket
import platform.posix.usleep
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Seam-level unit tests for `KqueueEventLoop` syscall error branches
 * via `FakeKqueueSyscallOps` injection. Covers the init failure cleanup
 * paths and register/registerCallback failure recovery that were
 * introduced in PR #355 but were only reachable through a real BSD
 * kernel failure (not testable in integration).
 *
 * Part of the project's two-layer seam + integration testing strategy
 * (this file covers the seam side).
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
        // Registration funnels to the EventLoop thread, so the loop has to be
        // running for the failing addWriteFilter to happen at all. What this
        // pins is the failure's effect — the continuation resumes with the
        // error — not which thread performs the syscall.
        fake.liveMode = true
        val el = KqueueEventLoop(logger, syscallOps = fake)
        el.start()
        // A real descriptor: the failure path closes the fd it was given, so a
        // fabricated number would be a close(2) on whatever this process
        // happens to have open at that number.
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0, "could not open a socket to wait on")
        try {
            val ex = assertFailsWith<IllegalStateException> {
                runBlocking { withTimeout(15.seconds) { el.awaitWriteReady(fd, logger = logger) } }
            }
            assertTrue(ex.message!!.contains("kevent"))
            // `fd=$fd`, not the bare number: a real descriptor is a small
            // integer that could turn up anywhere in the message.
            assertTrue(ex.message!!.contains("fd=$fd"), "expected the failing fd to be named: ${ex.message}")
        } finally {
            el.close()
        }
    }

    @Test
    fun `a failed arm releases the connect socket the waiter was given`() {
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            scriptAddFilterResult(0) // init succeeds
            scriptAddFilterResult(ENFILE) // the arm driven below fails
        }
        fake.liveMode = true
        val el = KqueueEventLoop(logger, syscallOps = fake)
        el.start()
        // A real descriptor, because whether it was released is the whole
        // assertion. `connect()` holds no other handle on it: the transport
        // that would own it is built after this wait returns, so an fd left
        // open here is unreachable for the life of the process.
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0, "could not open a socket to wait on")
        try {
            assertFailsWith<IllegalStateException> {
                runBlocking { withTimeout(15.seconds) { el.awaitWriteReady(fd, logger) } }
            }
            // Polled, not asserted outright: the release is claimed on this
            // thread and performed on the loop, so it is ordered after any arm
            // still queued for this fd rather than immediate.
            awaitFdClosed(fd)
        } finally {
            // No close here on purpose: if the assertion above failed, the fd
            // is open and leaks one descriptor in a test process that is about
            // to report a failure. Closing it would mean re-testing the number
            // first, and acting on that answer is the recycling hazard this
            // whole change is about.
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
            scriptWaitFailure(EINTR) // 1st: retriable, loop should `continue`
            scriptWaitFailure(EBADF) // 2nd: fatal, loop should log + break
        }
        val el = KqueueEventLoop(logger = levelRecordingLogger(LogLevel.ERROR, errors), syscallOps = fake)
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
        val el = KqueueEventLoop(logger = levelRecordingLogger(LogLevel.ERROR, errors), syscallOps = fake)
        el.loop()
        assertEquals(2, fake.waitCalls, "EAGAIN should be retried, then EBADF terminates")
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("kevent()"))
    }

    @Test
    fun `loop exits immediately on fatal waitEvents errno`() {
        val errors = mutableListOf<String>()
        val fake = FakeKqueueSyscallOps().apply {
            scriptWaitFailure(EBADF) // fatal on first call
        }
        val el = KqueueEventLoop(logger = levelRecordingLogger(LogLevel.ERROR, errors), syscallOps = fake)
        el.loop()
        assertEquals(1, fake.waitCalls, "fatal errno on first call should not retry")
        assertEquals(1, errors.size)
        assertTrue(errors.first().contains("kevent()"))
    }

    @Test
    fun `a failed callback arm withdraws the listener`() {
        // The hand-written half of the shared hook. epoll has the same test;
        // this one is the copy epoll's fix was modelled on, and the base's
        // FakeLoop cannot see either -- it stubs the hook with a list append.
        val errors = mutableListOf<String>()
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            scriptAddFilterResult(0) // loop init arms its own wakeup fd
            scriptAddFilterResult(ENOMEM) // the arm for fd 5000 fails
            scriptWaitFailure(EBADF) // terminate loop()
        }
        val el = KqueueEventLoop(logger = levelRecordingLogger(LogLevel.ERROR, errors), syscallOps = fake)
        try {
            el.registerCallback(
                5000,
                Interest.READ,
                object : FdReadyListener {
                    override fun onReady(interest: Interest) = Unit
                },
            )

            el.loop()

            assertFalse(
                el.hasCallbackRegistration(fd = 5000, interest = Interest.READ),
                "a listener whose arm failed must not stay in the ledger unarmed",
            )
            assertTrue(errors.any { it.contains("readiness callback will not fire") }, "reported at ERROR: $errors")
        } finally {
            el.close()
        }
    }

    // --- dispatchReady stale-filter removal tests (stale-event filter starvation) ---
    //
    // kqueue uses persistent EV_ADD filters: EVFILT_WRITE fires on every kevent()
    // call while the fd is writable. Without EV_DELETE after a completed flush,
    // the EventLoop spins in a busy loop — saturating the EventLoop thread and
    // starving accept() / reads under load (same root cause as the epoll stale-filter fix / PR #447).
    //
    // Drive `loop()` directly on the test thread. Each test scripts exactly one
    // EVFILT_WRITE event followed by a fatal EBADF to terminate the loop.

    @Test
    fun `WRITE callback that does not re-register causes deleteWriteFilter`() {
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            // addFilter queue empty → all addFilter calls succeed (default 0)
            scriptWaitOk(Triple(5000, EVFILT_WRITE, 0)) // fd 5000 writable
            scriptWaitFailure(EBADF) // terminate loop
        }
        val el = KqueueEventLoop(logger, syscallOps = fake)
        // Callback does NOT re-register: simulates a flush that completed fully.
        el.registerCallback(
            5000,
            Interest.WRITE,
            object : FdReadyListener {
                override fun onReady(interest: Interest) { /* no-op */ }
            },
        )
        el.loop()
        assertEquals(
            1,
            fake.deleteFilterCalls.size,
            "deleteWriteFilter must be called when callback does not re-register",
        )
        assertEquals(FakeKqueueSyscallOps.FilterKind.WRITE, fake.deleteFilterCalls[0].filter)
        assertEquals(5000, fake.deleteFilterCalls[0].fd)
    }

    @Test
    fun `WRITE callback that re-registers does not call deleteWriteFilter`() {
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            scriptWaitOk(Triple(5000, EVFILT_WRITE, 0)) // first WRITE fire
            scriptWaitFailure(EBADF)
        }
        val el = KqueueEventLoop(logger, syscallOps = fake)
        // Callback re-registers: simulates a partial flush that needs another WRITE event.
        el.registerCallback(
            5000,
            Interest.WRITE,
            object : FdReadyListener {
                override fun onReady(interest: Interest) {
                    el.registerCallback(
                        5000,
                        interest,
                        object : FdReadyListener {
                            override fun onReady(interest: Interest) {
                                /* second callback; never fires in this test */
                            }
                        },
                    )
                }
            },
        )
        el.loop()
        assertTrue(fake.deleteFilterCalls.isEmpty(), "deleteWriteFilter must NOT be called when callback re-registers")
    }

    @Test
    fun `stale EVFILT_WRITE with no handler emits WARN and calls deleteWriteFilter`() {
        val warns = mutableListOf<String>()
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            scriptWaitOk(Triple(5000, EVFILT_WRITE, 0)) // stale: no handler for fd 5000
            scriptWaitFailure(EBADF)
        }
        val el = KqueueEventLoop(logger = levelRecordingLogger(LogLevel.WARN, warns), syscallOps = fake)
        el.loop()
        assertEquals(1, fake.deleteFilterCalls.size, "deleteWriteFilter must be called for stale interest")
        assertEquals(FakeKqueueSyscallOps.FilterKind.WRITE, fake.deleteFilterCalls[0].filter)
        assertEquals(5000, fake.deleteFilterCalls[0].fd)
        assertEquals(1, warns.size, "stale interest must produce exactly one WARN log")
        assertTrue(warns.first().contains("stale"), "WARN must mention 'stale'")
    }

    /**
     * Logger that captures messages at exactly [level] into [sink].
     * All other levels are discarded.
     */
    @Test
    fun `a throwing dispatched task does not kill the loop or skip later tasks`() {
        val warns = mutableListOf<String>()
        val fake = FakeKqueueSyscallOps().apply {
            scriptWaitFailure(EBADF) // terminate loop() after the first drain
        }
        val el = KqueueEventLoop(logger = levelRecordingLogger(LogLevel.WARN, warns), syscallOps = fake)
        var laterTaskRan = false
        // Both tasks are queued before loop() runs, so they land in the same
        // drain batch: the guard must run the second despite the first throwing.
        el.dispatch(EmptyCoroutineContext, Runnable { throw IllegalStateException("boom") })
        el.dispatch(EmptyCoroutineContext, Runnable { laterTaskRan = true })
        el.loop()
        assertTrue(laterTaskRan, "the task queued after the throwing one must still run")
        assertEquals(1, warns.size, "the throwing task should produce exactly one warn log")
        assertTrue(warns.first().contains("task"), "warn should mention the failing task, got: ${warns.first()}")
    }

    // --- loop teardown sweeps the suspend ledger ---

    @Test
    fun `loop ends a waiter it will never arm and its handler releases the socket`() {
        // The wiring, not the base class: this drives the real loop() to its
        // fatal-errno exit and asserts the waiter it left behind was ended.
        // That exit is also the case with no close() in flight, so the sweep
        // is the only thing that can end it.
        val fake = FakeKqueueSyscallOps().apply { scriptWaitFailure(EBADF) }
        val el = KqueueEventLoop(logger, syscallOps = fake)
        // A real descriptor, because the cancellation handler closes it and
        // whether it did is the second half of what this asserts.
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0, "could not open a socket to wait on")

        val failure = CompletableDeferred<Throwable>()
        runBlocking {
            withTimeout(15.seconds) {
                launch {
                    try {
                        el.awaitWriteReady(fd, logger)
                    } catch (t: Throwable) {
                        failure.complete(t)
                    }
                }
                yield() // let it register and park before the loop runs

                el.loop()

                val ended = failure.await()
                assertTrue(
                    ended is CancellationException,
                    "the waiter must end as a cancellation, not a failure that cancels its parent: $ended",
                )
                assertTrue(
                    ended.message?.contains("EventLoop stopped before arming") == true,
                    "expected the sweep's cancellation, got: $ended",
                )
                assertEquals(
                    -1,
                    fcntl(fd, F_GETFD),
                    "the cancellation handler must have closed the connect socket",
                )
            }
        }
    }

    /**
     * Waits until [fd] is closed, bounded by wall clock.
     *
     * The release is handed to the loop, so it is not observable the instant
     * the wait throws. A deadline rather than a bare spin: a fix that stopped
     * releasing must fail this test, not hang it.
     */
    private fun awaitFdClosed(fd: Int) {
        val deadline = TimeSource.Monotonic.markNow() + FD_CLOSE_BUDGET
        while (fcntl(fd, F_GETFD) != -1) {
            assertTrue(
                deadline.hasNotPassedNow(),
                "the fd the waiter owned was still open $FD_CLOSE_BUDGET after the wait ended",
            )
            usleep(FD_CLOSE_POLL_US)
        }
    }

    private fun levelRecordingLogger(captured: LogLevel, sink: MutableList<String>): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == captured
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == captured) sink.add(message.toString())
        }
    }

    @Test
    fun `closing a loop that never started still runs what was handed to it`() {
        // A loop can be closed without ever having run: a group whose `start()`
        // fails part way leaves the rest constructed and idle. Nothing has
        // published `finished` or `quiescent` for it, so the hand-off reads
        // "live" and offers -- and no drain ever comes. Before this, the work
        // sat in that queue for the loop object's lifetime: a transport's
        // teardown, and with it the descriptor it would have released.
        val loop = KqueueEventLoop(NoopLoggerFactory.logger("KqueueEventLoopSeamTest"))
        var onLoop = 0
        var ifStopped = 0

        loop.runOnLoop(onLoop = { onLoop++ }, ifStopped = { ifStopped++ })

        assertEquals(0, onLoop, "premise: nothing runs it yet -- there is no thread to run it")
        assertEquals(0, ifStopped, "premise: the loop does not look stopped, because nothing said so")

        loop.close()

        assertEquals(1, onLoop + ifStopped, "close must run what was handed over, by one route or the other")
    }

    @Test
    fun `a loop started after it was closed does not take the ledgers back`() {
        // The claim is what lets the closing thread walk state the loop owns.
        // A `start()` arriving afterwards must find it gone: a second walker
        // would run the terminal sequence against ledgers already swept, on a
        // thread the first one does not know about.
        val loop = KqueueEventLoop(NoopLoggerFactory.logger("KqueueEventLoopSeamTest"))
        loop.close()

        assertTrue(loop.isStopped(), "premise: the closing thread ran the terminal sequence")

        loop.start()

        // No thread is created at all. It is not enough for one to start and
        // find the claim taken: `threadPtr` lives in the arena that `close()`
        // has already released, so `pthread_create` would write through a
        // dangling pointer -- which showed up as a crash three tests later,
        // not here.
        assertTrue(loop.isStopped(), "a loop closed before it ever ran must not come back")
        loop.close()
    }

    private companion object {
        /** Poll step while waiting for the loop to perform a claimed release. */
        const val FD_CLOSE_POLL_US = 2_000u

        /** Wall-clock bound on that wait; generous, since it only has to exclude a hang. */
        val FD_CLOSE_BUDGET = 15.seconds
    }
}
