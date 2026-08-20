@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.readiness.FdReadyListener
import io.github.fukusaka.keel.native.readiness.Interest
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

    @Test
    fun `a failed arm releases the socket even when the waiter's dispatcher refuses`() {
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            scriptAddFilterResult(0) // init succeeds
            scriptAddFilterResult(ENFILE) // the arm driven below fails
        }
        fake.liveMode = true
        val el = KqueueEventLoop(logger, syscallOps = fake)
        el.start()
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0, "could not open a socket to wait on")
        val refusing = RefusingDispatcher()
        try {
            // A scope of its own, not this test's: the dispatcher refuses the
            // resumption, so this coroutine can never complete and a child of
            // the test would hold the test open. UNDISPATCHED so the wait
            // registers inline on this thread -- only the resume below asks the
            // dispatcher to take it back.
            CoroutineScope(refusing).launch(start = CoroutineStart.UNDISPATCHED) {
                el.awaitWriteReady(fd, logger)
            }
            // The failure this loop hands back never reaches the waiter's own
            // frame, so neither of the two endings that live there runs -- the
            // release hook the wait registered is what is left, and the base's
            // guarded hand-off is what invokes it. This engine reaches that
            // hand-off through `failUnarmedWaiter`; the sibling above drives
            // the same arm failure with a dispatcher that accepts, and so
            // passes either way. This is the one that fails if *this* engine
            // stops routing its failure through the base.
            awaitFdClosed(fd)
            assertEquals(1, refusing.attempts.value, "the seam must have reached the resume")
        } finally {
            // No close here on purpose, for the reason the sibling above states.
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
    fun `a loop that ends on a fatal wait errno records why`() {
        // This is how this engine ends on its own -- not by throwing, which a
        // pthread entry point cannot usefully do, but by breaking out of its
        // body. That return is the same shape as the one a stop request
        // produces, so a flush waiter is told the loop was asked to stop
        // unless the reason is written down before the loop goes.
        val fake = FakeKqueueSyscallOps().apply { scriptWaitFailure(EBADF) }
        val el = KqueueEventLoop(logger = logger, syscallOps = fake)

        el.loop()

        val fault = el.loopFailure()
        assertNotNull(fault, "a loop nobody asked to stop must record why it stopped")
        assertTrue(
            checkNotNull(fault.message).contains("kevent()"),
            "and name what failed, got: ${fault.message}",
        )
    }

    @Test
    fun `a loop asked to stop records nothing`() {
        // The other arm: without this, a record that was never conditional --
        // or one written unconditionally at the top of the body -- would turn
        // every ordinary shutdown into a reported fault.
        //
        // The stop has to arrive while the body is running. Closing first and
        // then calling loop() looks like the same thing and is not: the close
        // takes the termination claim, so loop() returns at its guard and the
        // body -- the code under test -- never runs. Closing from inside the
        // wait leaves the body to exit through its own condition, which is
        // what an ordinary shutdown does.
        val fake = FakeKqueueSyscallOps()
        val el = KqueueEventLoop(logger = logger, syscallOps = fake)
        // Bounded: nothing else ends this loop, so a close that stopped taking
        // the running flag down -- or a body that stopped reading it -- would
        // spin here rather than fail. A scripted fatal cannot serve instead;
        // it would end the loop for the wrong reason and decide the assertion.
        var waits = 0
        fake.onWait = {
            check(++waits <= MAX_WAITS) { "the loop did not end when it was asked to" }
            el.close()
        }

        el.loop()

        assertEquals(1, fake.waitCalls, "the body must have run and ended through its own condition")
        assertNull(el.loopFailure(), "an ordinary stop is not a fault")
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

    /**
     * Refuses every resumption handed to it, the way a dispatcher backed by a
     * pool shut down under the waiter does. [attempts] is atomic because the
     * dispatch happens on the EventLoop thread and the assertion reads it on
     * the test thread.
     */
    private class RefusingDispatcher : CoroutineDispatcher() {
        val attempts: AtomicInt = AtomicInt(0)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            attempts.incrementAndGet()
            throw IllegalStateException("dispatcher refused the resumed continuation")
        }
    }

    private fun levelRecordingLogger(captured: LogLevel, sink: MutableList<String>): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == captured
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == captured) sink.add(message.toString())
        }
    }

    @Test
    fun `closing a loop that ran on the caller's own thread does not try to join one`() {
        // `loop()` is callable directly -- the seam suites do it -- and such a
        // loop has no thread. Closing it finds the termination already claimed,
        // and the mistake to avoid is treating that like "a thread has it":
        // `threadPtr` was never written, so handing that slot to `pthread_join`
        // reads whatever the arena held. There is nothing to join; there is
        // only something to release.
        val warnings = mutableListOf<String>()
        val fake = FakeKqueueSyscallOps().apply {
            scriptKqueueCreateFd(fd = 1000)
            scriptMakePipeFds(readFd = 1001, writeFd = 1002)
            scriptAddFilterResult(0) // loop init arms its own wakeup fd
            scriptWaitFailure(EBADF) // terminate loop()
        }
        val loop = KqueueEventLoop(levelRecordingLogger(LogLevel.WARN, warnings), syscallOps = fake)
        loop.loop()

        loop.close()

        // The join itself is not observable here: on one target the always-true
        // guard hands `pthread_join` a null and the process dies rather than
        // logging, and on the other the guard skips the join anyway, so the
        // pre-fix code is just as quiet. What the fix does change on every
        // target is that this branch never reaches the wakeup the join path
        // issues first -- and the fake counts it.
        assertEquals(0, fake.wakeupWriteCalls, "the never-started branch neither wakes nor joins")
        assertTrue(
            warnings.none { "pthread_join" in it },
            "and reports no join failure either: $warnings",
        )
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

        // The route matters, not just that something ran: the work was queued
        // for the loop, so the terminal sequence's drain must run it. Falling
        // back on the caller instead would be a different contract -- the
        // fallback is documented as not acting on state the loop owned.
        assertEquals(1, onLoop, "the queued work runs, on the thread that claimed the loop")
        assertEquals(0, ifStopped, "and not as the off-loop fallback")
    }

    @Test
    fun `a loop started after it was closed does not take the ledgers back`() {
        // The claim is what lets the closing thread walk state the loop owns.
        // A `start()` arriving afterwards must find it gone: a second walker
        // would run the terminal sequence against ledgers already swept, on a
        // thread the first one does not know about.
        val errors = mutableListOf<String>()
        val loop = KqueueEventLoop(levelRecordingLogger(LogLevel.ERROR, errors))
        loop.close()

        assertTrue(loop.isStopped(), "premise: the closing thread ran the terminal sequence")

        loop.start()

        // Asserted on the refusal, not on the loop still being stopped:
        // `isStopped()` reads a latch that `start()` never touches, so it
        // holds whether or not a thread was created. What must not happen is
        // the creation itself -- `threadPtr` lives in the arena `close()` has
        // already released, so `pthread_create` writes through a dangling
        // pointer. That showed up as a crash three tests later, never here.
        assertTrue(
            errors.any { "termination is already claimed is ignored" in it },
            "starting a closed loop must be refused outright: $errors",
        )
        loop.close()
    }

    private companion object {
        /**
         * How many waits the case that ends the loop by closing may take
         * before it is a hang. One is what it produces; the rest is slack
         * rather than a second path anything takes.
         */
        const val MAX_WAITS = 8

        /** Poll step while waiting for the loop to perform a claimed release. */
        const val FD_CLOSE_POLL_US = 2_000u

        /** Wall-clock bound on that wait; generous, since it only has to exclude a hang. */
        val FD_CLOSE_BUDGET = 15.seconds
    }
}
