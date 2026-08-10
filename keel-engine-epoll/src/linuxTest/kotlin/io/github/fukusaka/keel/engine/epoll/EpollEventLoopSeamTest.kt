package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.linux.EPOLLERR
import platform.linux.EPOLLHUP
import platform.linux.EPOLLIN
import platform.linux.EPOLLOUT
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EBADF
import platform.posix.EINTR
import platform.posix.EMFILE
import platform.posix.ENOSPC
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
 * Seam-level unit tests for `EpollEventLoop` syscall error branches
 * via `FakeEpollSyscallOps` injection. Covers the init failure cleanup
 * paths and `addOrModifyEpoll` / `removeInterest` error logging
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

    /**
     * No-op `FdReadyListener` for tests that only need the registration side
     * effect (epoll_ctl call, callback bookkeeping). Replaces the SAM-lambda
     * style retired when [FdReadyListener] gained an
     * `onPeerClosed` default-no-op method.
     */
    private object NoOpListener : FdReadyListener {
        override fun onReady(interest: Interest) { /* no-op */ }
    }

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

    // --- register() failure paths ---

    @Test
    fun `a failed arm releases the connect socket the waiter was given`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD (wakeupFd) succeeds
            scriptAddResult(ENOSPC) // the arm driven below fails
        }
        fake.liveMode = true
        val el = EpollEventLoop(logger, syscallOps = fake)
        el.start()
        // A real descriptor, because whether it was released is the whole
        // assertion. `connect()` holds no other handle on it: the transport
        // that would own it is built after this wait returns, so an fd left
        // open here is unreachable for the life of the process.
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0, "could not open a socket to wait on")
        try {
            assertFailsWith<IllegalStateException> {
                runBlocking { withTimeout(DRAIN_BUDGET) { el.awaitWriteReady(fd, logger) } }
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
    fun `releasing a cancelled connect socket lets the same fd number be armed again`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD (wakeupFd)
            scriptAddResult(0) // the connect wait's arm succeeds
            scriptAddResult(0) // the re-arm this test is about
        }
        fake.liveMode = true
        val el = EpollEventLoop(logger, syscallOps = fake)
        el.start()
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0, "could not open a socket to wait on")
        // The fake publishes its counter only when the watched fd is the one
        // being armed, but the value it publishes is the total number of calls
        // — so the wakeup fd's ADD at init is included in the figures below.
        fake.watchedFd = fd
        try {
            runBlocking {
                withTimeout(DRAIN_BUDGET) {
                    val waiter = launch { el.awaitWriteReady(fd, logger) }
                    // Let it register and hand the arm to the loop before this
                    // thread blocks polling — they share the runBlocking
                    // dispatcher, so blocking first would starve the launch.
                    yield()
                    // Init's ADD plus this arm. Once it lands, the loop's
                    // mask for fd says EPOLLOUT.
                    awaitCtlCalls(fake, expected = 2)
                    waiter.cancelAndJoin()
                }
            }
            // The release closed fd, so the kernel may hand this number to the
            // next socket. Arming that number for WRITE has to reach epoll_ctl:
            // if the loop still believes EPOLLOUT is set, addOrModifyEpoll sees
            // no change and skips the syscall, and the new socket's waiter
            // parks with nothing watching it.
            //
            // Arming a number this process has closed is only legal because the
            // syscalls are faked — against the real ops this would be the very
            // hazard under test. The release is queued to the loop ahead of this
            // registration, so the loop performs them in that order.
            el.registerCallback(fd = fd, interest = Interest.WRITE, listener = NoOpListener)
            awaitCtlCalls(fake, expected = 3)
            val reArm = fake.ctlCalls.last()
            assertEquals(
                FakeEpollSyscallOps.CtlOp.ADD,
                reArm.op,
                "the re-arm must be an ADD, not a skipped call: ${fake.ctlCalls}",
            )
            assertEquals(fd, reArm.fd)
        } finally {
            el.close()
        }
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
        fake.liveMode = true
        fake.watchedFd = 2000
        val el = EpollEventLoop(logger, syscallOps = fake)
        try {
            // Trigger addOrModifyEpoll via registerCallback (fd 2000, READ).
            // Registration funnels to the loop, so the loop must be running for
            // the syscall to happen at all — this test is about the EEXIST
            // fallback, not about which thread performs it.
            el.registerCallback(fd = 2000, interest = Interest.READ, listener = NoOpListener)
            el.start()
            awaitCtlCalls(fake, expected = 3)
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

    // --- EventLoop-thread funnel pin ---
    //
    // `register` / `registerCallback` funnel the `epoll_ctl` syscall through
    // the owning EventLoop thread:
    //
    //   if (inEventLoop()) submitArmCallback(fd, interest, key)
    //   else dispatch(EmptyCoroutineContext, Runnable { submitArmCallback(fd, interest, key) })
    //
    // These two pin the pre-start case. It used to be an exception: an extra
    // disjunct, `eventLoopThread == null`, sent registrations issued before the
    // loop started down the inline path, and the tests here asserted that as the
    // contract. It was not safe — `accept()` registers on the caller's thread,
    // so the window between `pthread_create` returning and `loop()` assigning
    // the handle let a registration read null, go inline, and then fail its own
    // `assertInEventLoop` when the loop assigned the handle in between. The
    // funnel now holds without exception, and what these guard is that a
    // pre-start registration waits for the loop rather than running on whoever
    // happened to call.

    @Test
    fun `registerCallback READ pre-start queues the syscall for the loop`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD (wakeupFd)
            scriptAddResult(0) // ADD for fd 2000
        }
        fake.liveMode = true
        fake.watchedFd = 2000
        val el = EpollEventLoop(logger, syscallOps = fake)
        try {
            el.registerCallback(fd = 2000, interest = Interest.READ, listener = NoOpListener)
            assertEquals(
                1,
                fake.ctlCalls.size,
                "only the init ADD may have run — the registration belongs on the loop, not the caller",
            )

            el.start()
            awaitCtlCalls(fake, expected = 2)
            assertEquals(2000, fake.ctlCalls[1].fd)
            assertEquals(FakeEpollSyscallOps.CtlOp.ADD, fake.ctlCalls[1].op)
        } finally {
            el.close()
        }
    }

    @Test
    fun `registerCallback WRITE pre-start queues the syscall for the loop`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD (wakeupFd)
            scriptAddResult(0) // ADD for fd 3000 (callback path)
        }
        fake.liveMode = true
        fake.watchedFd = 3000
        val el = EpollEventLoop(logger, syscallOps = fake)
        try {
            el.registerCallback(fd = 3000, interest = Interest.WRITE, listener = NoOpListener)
            assertEquals(
                1,
                fake.ctlCalls.size,
                "only the init ADD may have run — the registration belongs on the loop, not the caller",
            )

            el.start()
            awaitCtlCalls(fake, expected = 2)
            assertEquals(3000, fake.ctlCalls[1].fd)
            assertEquals(FakeEpollSyscallOps.CtlOp.ADD, fake.ctlCalls[1].op)
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
            scriptWaitFailure(EINTR) // 1st: retriable, loop should `continue`
            scriptWaitFailure(EBADF) // 2nd: fatal, loop should log + break
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
            scriptWaitFailure(EBADF) // fatal on first call
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
            scriptWaitOk(2000 to EPOLLHUP) // EPOLLHUP only — no EPOLLIN
            scriptWaitFailure(EBADF) // terminate loop
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        var readCalled = false
        el.registerCallback(
            fd = 2000,
            interest = Interest.READ,
            listener = object : FdReadyListener {
                override fun onReady(interest: Interest) {
                    readCalled = true
                }
            },
        )
        el.loop()
        assertTrue(readCalled, "READ callback must fire on EPOLLHUP even without EPOLLIN")
    }

    @Test
    fun `EPOLLERR-only event invokes READ callback`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0)
            scriptWaitOk(2000 to EPOLLERR) // EPOLLERR only — no EPOLLIN
            scriptWaitFailure(EBADF)
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        var readCalled = false
        el.registerCallback(
            fd = 2000,
            interest = Interest.READ,
            listener = object : FdReadyListener {
                override fun onReady(interest: Interest) {
                    readCalled = true
                }
            },
        )
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
        el.registerCallback(
            fd = 2000,
            interest = Interest.WRITE,
            listener = object : FdReadyListener {
                override fun onReady(interest: Interest) {
                    writeCalled = true
                }
            },
        )
        el.loop()
        assertFalse(writeCalled, "WRITE callback must NOT fire on EPOLLHUP alone")
    }

    // --- stale EPOLLOUT removal tests ---
    //
    // A WRITE callback that does not re-register after onReady() (successful
    // flush) must cause EPOLLOUT to be removed from the epoll filter.
    // Without this, level-triggered epoll keeps reporting EPOLLOUT on every
    // wait iteration — a busy loop that starves I/O processing under load.

    @Test
    fun `WRITE callback that does not re-register gets its EPOLLOUT taken back`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD for wakeupFd
            scriptAddResult(0) // ADD for fd 2000 WRITE interest
            scriptWaitOk(2000 to EPOLLOUT) // WRITE ready — no re-arm from callback
            scriptWaitFailure(EBADF) // terminate loop
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        var writeCalled = false
        // Register a WRITE callback that intentionally does NOT re-arm,
        // simulating a flush that completed successfully (no more EAGAIN).
        el.registerCallback(
            fd = 2000,
            interest = Interest.WRITE,
            listener = object : FdReadyListener {
                override fun onReady(interest: Interest) {
                    writeCalled = true
                    // Deliberately NOT calling registerCallback again.
                }
            },
        )
        el.loop()
        assertTrue(writeCalled, "WRITE callback must be invoked")
        // After a no-re-arm WRITE callback the fd must no longer be armed for
        // EPOLLOUT, or it busy-loops. Asserted on the outcome rather than the
        // opcode: with nothing else armed the engine drops the fd (DEL), and
        // with another interest still live it narrows the mask (MOD).
        val last = fake.ctlCalls.last { it.fd == 2000 }
        assertTrue(
            last.op == FakeEpollSyscallOps.CtlOp.DEL || (last.events and EPOLLOUT) == 0,
            "stale EPOLLOUT must be taken back, got op=${last.op} events=${last.events}",
        )
    }

    @Test
    fun `WRITE callback that re-registers does not remove EPOLLOUT from epoll`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD for wakeupFd
            scriptAddResult(0) // ADD for fd 2000 (first registerCallback)
            // First wait: WRITE fires, callback re-arms (still EAGAIN).
            scriptWaitOk(2000 to EPOLLOUT)
            // Second wait: WRITE fires again, callback does not re-arm.
            scriptWaitOk(2000 to EPOLLOUT)
            scriptWaitFailure(EBADF)
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        var callCount = 0
        // FdReadyListener that re-registers on the first call (EAGAIN still
        // pending), then stops on the second (flush succeeded).
        val listener = object : FdReadyListener {
            override fun onReady(interest: Interest) {
                callCount++
                if (callCount == 1) {
                    // Still EAGAIN — re-arm the WRITE callback.
                    el.registerCallback(fd = 2000, interest = interest, listener = this)
                }
                // Second call: no re-arm.
            }
        }
        el.registerCallback(fd = 2000, interest = Interest.WRITE, listener = listener)
        el.loop()
        assertEquals(2, callCount, "WRITE callback must be invoked exactly twice")
        // On the first fire the callback re-registered, so EPOLLOUT stays armed
        // and nothing is taken back between the two waits. On the second fire it
        // did not, so exactly one take-back follows. Asserted on the outcome
        // rather than the opcode: nothing else is armed here, so the engine
        // drops the fd (DEL) rather than narrowing its mask (MOD).
        val takeBacks = fake.ctlCalls.filter {
            it.fd == 2000 &&
                (it.op == FakeEpollSyscallOps.CtlOp.DEL || (it.events and EPOLLOUT) == 0)
        }
        assertEquals(1, takeBacks.size, "exactly one take-back (after the second fire) expected")
    }

    // --- stale interest safety-net tests ---
    //
    // When an epoll event fires but no handler (callback or suspend waiter)
    // is registered for that fd+interest, dispatchReady must WARN and call
    // removeInterest to prevent a level-triggered busy loop. This
    // can happen if a callback is unregistered after the interest was armed
    // but before the event fires.

    @Test
    fun `stale WRITE interest with no handler logs WARN and gets EPOLLOUT taken back`() {
        val warns = mutableListOf<String>()
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD for wakeupFd
            scriptAddResult(0) // ADD for fd 2000 via registerCallback
            // Stale EPOLLOUT fires after the callback was unregistered.
            scriptWaitOk(2000 to EPOLLOUT)
            scriptWaitFailure(EBADF) // terminate loop
        }
        val el = EpollEventLoop(logger = recordingWarnLogger(warns), syscallOps = fake)
        // Register then immediately unregister: interest stays armed in epoll
        // (unregisterCallback removes from callbackRegistrations but not fdEvents).
        el.registerCallback(fd = 2000, interest = Interest.WRITE, listener = NoOpListener)
        el.unregisterCallback(fd = 2000, interest = Interest.WRITE)
        el.loop()
        assertEquals(1, warns.size, "stale event must produce exactly one WARN")
        assertTrue(warns.first().contains("2000"), "WARN must mention the fd")
        val last = fake.ctlCalls.last { it.fd == 2000 }
        assertTrue(
            last.op == FakeEpollSyscallOps.CtlOp.DEL || (last.events and EPOLLOUT) == 0,
            "the stale EPOLLOUT must be taken back, got op=${last.op} events=${last.events}",
        )
    }

    @Test
    fun `READ callback that re-registers via armRead does not trigger epoll_ctl MOD`() {
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptAddResult(0) // init ADD
            scriptAddResult(0) // ADD for fd 2000
            scriptWaitOk(2000 to EPOLLIN)
            scriptWaitFailure(EBADF)
        }
        val el = EpollEventLoop(logger, syscallOps = fake)
        var readCalled = false
        // READ callback that immediately re-arms (simulates armRead in the pipeline).
        el.registerCallback(
            fd = 2000,
            interest = Interest.READ,
            listener = object : FdReadyListener {
                override fun onReady(interest: Interest) {
                    readCalled = true
                    // Re-arm: mirrors what EpollIoTransport.armRead() does.
                    el.registerCallback(
                        fd = 2000,
                        interest = interest,
                        listener = object : FdReadyListener {
                            override fun onReady(interest: Interest) { /* no-op */ }
                        },
                    )
                }
            },
        )
        el.loop()
        assertTrue(readCalled)
        // Re-registration during onReady() means removeInterest is not
        // called — no MOD at all on the read hot path.
        val modCalls = fake.ctlCalls.filter { it.op == FakeEpollSyscallOps.CtlOp.MOD }
        assertTrue(modCalls.isEmpty(), "No MOD expected when READ callback re-arms synchronously")
    }

    /**
     * Logger that captures `error`-level messages into [sink]. Other levels
     * are discarded. Used by the main-loop error-branch tests to assert
     * the fatal-exit path emits the expected log.
     */
    @Test
    fun `a throwing dispatched task does not kill the loop or skip later tasks`() {
        val warns = mutableListOf<String>()
        val fake = FakeEpollSyscallOps().apply {
            scriptWaitFailure(EBADF) // terminate loop() after the first drain
        }
        val el = EpollEventLoop(logger = recordingWarnLogger(warns), syscallOps = fake)
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
        val fake = FakeEpollSyscallOps().apply { scriptWaitFailure(EBADF) }
        val el = EpollEventLoop(logger, syscallOps = fake)
        // A real descriptor, because the cancellation handler closes it and
        // whether it did is the second half of what this asserts.
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        assertTrue(fd >= 0, "could not open a socket to wait on")

        val failure = CompletableDeferred<Throwable>()
        runBlocking {
            withTimeout(DRAIN_BUDGET) {
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
     * Records every level, unlike [recordingLogger].
     *
     * The join failure a test may need to rule out is logged at WARN, and an
     * ERROR-only sink would pass whatever happened.
     */
    private fun allLevelRecordingLogger(sink: MutableList<String>): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = true
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            sink.add(message.toString())
        }
    }

    private fun recordingLogger(sink: MutableList<String>): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.ERROR
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.ERROR) sink.add(message.toString())
        }
    }

    /**
     * Logger that captures `warn`-level messages into [sink]. Other levels
     * are discarded. Used by the stale-interest safety-net tests.
     */
    private fun recordingWarnLogger(sink: MutableList<String>): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.WARN
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.WARN) sink.add(message.toString())
        }
    }

    @Test
    fun `a failed callback arm withdraws the listener`() {
        // kqueue's submitArmCallback has always done this. epoll's discarded the
        // errno, so a first ADD failing (ENOSPC on max_user_watches, EPERM on a
        // non-pollable fd) left the listener in the ledger with the fd not in the
        // interest list at all: no event of any kind is ever delivered for it and
        // the connection is silently dead.
        //
        // Driven on the test thread, like kqueue's copy of this test. Under
        // start() the assertions would read `errors` -- a plain MutableList the
        // loop thread appends to -- with no happens-before edge, and the only
        // signal available to wait on (the ledger withdrawal) is published
        // strictly *before* the append, so gating on it would not supply one.
        val errors = mutableListOf<String>()
        val fake = FakeEpollSyscallOps().apply {
            scriptAddResult(0) // init ADD (wakeupFd)
            scriptAddResult(ENOSPC) // ADD for fd 2000 fails
            scriptWaitFailure(EBADF) // terminate loop()
        }
        val el = EpollEventLoop(logger = recordingLogger(errors), syscallOps = fake)
        try {
            el.registerCallback(fd = 2000, interest = Interest.READ, listener = NoOpListener)

            el.loop()

            assertFalse(
                el.hasCallbackRegistration(fd = 2000, interest = Interest.READ),
                "a listener whose arm failed must not stay in the ledger unarmed",
            )
            assertTrue(
                errors.any { it.contains("readiness callback will not fire") },
                "the discarded errno is the defect this fixes; report it: $errors",
            )
        } finally {
            el.close()
        }
    }

    /**
     * Waits until the EventLoop has recorded [expected] `epoll_ctl` calls,
     * bounded by wall clock.
     *
     * Gated on [FakeEpollSyscallOps.ctlCallCount], which the fake publishes
     * *after* appending to `ctlCalls`. That ordering is what makes the list
     * safe to read once this returns — the volatile's release edge covers the
     * append, so an acquire of it here makes the entry visible. Polling
     * `ctlCalls.size` directly would be an unsynchronised read of a plain
     * `MutableList` the loop thread is appending to; polling either without a
     * deadline would be an unbounded spin, which is a MUST violation for
     * anything waiting on dispatch to complete.
     */
    private fun awaitCtlCalls(fake: FakeEpollSyscallOps, expected: Int) {
        val deadline = TimeSource.Monotonic.markNow() + DRAIN_BUDGET
        while (fake.ctlCallCount < expected) {
            check(deadline.hasNotPassedNow()) {
                "the EventLoop recorded ${fake.ctlCallCount} of $expected epoll_ctl calls within $DRAIN_BUDGET"
            }
            usleep(POLL_US)
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
        val deadline = TimeSource.Monotonic.markNow() + DRAIN_BUDGET
        while (fcntl(fd, F_GETFD) != -1) {
            assertTrue(
                deadline.hasNotPassedNow(),
                "the fd the waiter owned was still open $DRAIN_BUDGET after the wait ended",
            )
            usleep(POLL_US)
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
        val errors = mutableListOf<String>()
        val fake = FakeEpollSyscallOps().apply {
            scriptAddResult(0) // init ADD (wakeupFd)
            scriptWaitFailure(EBADF) // terminate loop()
        }
        val loop = EpollEventLoop(logger = allLevelRecordingLogger(errors), syscallOps = fake)
        loop.loop()

        loop.close()

        assertTrue(
            errors.none { "pthread_join" in it },
            "no join may be attempted for a thread that was never created: $errors",
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
        val loop = EpollEventLoop(NoopLoggerFactory.logger("EpollEventLoopSeamTest"))
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
        val loop = EpollEventLoop(recordingLogger(errors))
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
            errors.any { "start() on a closed loop is ignored" in it },
            "starting a closed loop must be refused outright: $errors",
        )
        loop.close()
    }

    private companion object {
        /** Poll step while waiting for the loop to drain a queued registration. */
        const val POLL_US = 2_000u

        /** Wall-clock bound on that wait; generous, since it only has to exclude a hang. */
        val DRAIN_BUDGET = 15.seconds
    }
}
