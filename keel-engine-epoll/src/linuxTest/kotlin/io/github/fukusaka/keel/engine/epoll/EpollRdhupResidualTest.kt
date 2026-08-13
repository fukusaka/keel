package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.readiness.FdReadyListener
import io.github.fukusaka.keel.native.readiness.Interest
import platform.linux.EPOLLERR
import platform.linux.EPOLLIN
import platform.linux.EPOLLRDHUP
import platform.posix.EBADF
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Disarming READ has to take back every bit arming READ put in.
 *
 * `registerCallback` arms `EPOLLIN or EPOLLRDHUP` so peer FIN reaches the
 * listener even when the user never enables read. The disarm used to clear only
 * `EPOLLIN`, leaving `EPOLLRDHUP` armed with nothing able to dispatch it:
 * `loopBody` derives read-readiness from `EPOLLIN|EPOLLERR|EPOLLHUP`, which does
 * not include it. epoll here is level-triggered, so once the peer sent FIN the
 * condition was permanent — `epoll_wait` returned that fd on every iteration,
 * no handler ran, and the loop spun at 100% until the fd was closed.
 *
 * Driven through the syscall seam because the residual lives in the mask sent to
 * `epoll_ctl`, and through `dispatchReady`'s "no re-register" branch because
 * that is the path that leaves it: a listener that declines to re-arm (the
 * back-pressure case, `readEnabled = false`) is what triggers the disarm.
 *
 * The order that reaches this in production is data-then-FIN, which neither
 * sibling test covers — the peer-close test sends no data (READ is still armed
 * when FIN arrives, so `EPOLLIN` carries it) and the back-pressure test sends no
 * FIN.
 */
class EpollRdhupResidualTest {

    private val logger = NoopLoggerFactory.logger("EpollRdhupResidualTest")

    /** Declines to re-arm, exactly as the transport does with read disabled. */
    private class DecliningListener : FdReadyListener {
        var readyCount = 0
            private set

        override fun onReady(interest: Interest) {
            readyCount++
        }
    }

    @Test
    fun `an EOF dispatch keeps the interest of a listener that re-armed`() {
        val watched = 2000
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            // EPOLLERR sets eofFlag, and it is reported for a listening socket
            // whose accept failed — the case a server's AcceptArm re-arms from.
            scriptWaitOk(watched to EPOLLERR)
            scriptWaitFailure(EBADF)
        }
        val loop = EpollEventLoop(logger, syscallOps = fake)

        // Re-registers from inside onReady, exactly as AcceptArm does on
        // WouldBlock and on a failed accept.
        val listener = object : FdReadyListener {
            var readyCount = 0
            override fun onReady(interest: Interest) {
                readyCount++
                loop.registerCallback(watched, interest, this)
            }
        }
        loop.registerCallback(watched, Interest.READ, listener)
        loop.loop()

        assertEquals(1, listener.readyCount, "the listener should have been dispatched once")
        // The re-arm issues no syscall — the mask is unchanged — so the only
        // record of it is the registration itself. Disarming after it would
        // discard a live listener, and dropping the fd from the interest list
        // would take the always-reported EPOLLERR that revives it with them.
        val forFd = fake.ctlCalls.filter { it.fd == watched }
        assertTrue(
            forFd.none { it.op == FakeEpollSyscallOps.CtlOp.DEL },
            "the fd was dropped from the interest list although the listener re-armed: $forFd",
        )
    }

    @Test
    fun `a failing disarm is logged and leaves the loop running`() {
        val watched = 2000
        val errors = mutableListOf<String>()
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            scriptDelResult(EBADF)
            scriptWaitOk(watched to EPOLLIN)
            scriptWaitFailure(EBADF)
        }
        val loop = EpollEventLoop(recordingLogger(errors), syscallOps = fake)

        val listener = DecliningListener()
        loop.registerCallback(watched, Interest.READ, listener)
        loop.loop()

        // A refused DEL is a bookkeeping problem, not a reason to stop: the fd
        // stays in the kernel's interest list, and the next arm recovers it
        // through addOrModifyEpoll's ADD -> EEXIST -> MOD fallback. The loop
        // must reach its own exit rather than propagate the errno.
        assertEquals(1, listener.readyCount, "the listener should still have been dispatched")
        assertTrue(
            fake.ctlCalls.any { it.fd == watched && it.op == FakeEpollSyscallOps.CtlOp.DEL },
            "the disarm should have been attempted, got ${fake.ctlCalls.filter { it.fd == watched }}",
        )
        assertTrue(
            errors.none { it.contains("epoll_ctl") },
            "a refused disarm is debug-level, not an error: ${errors.firstOrNull()}",
        )
    }

    /** Captures ERROR-level output so the test can assert the loop stayed quiet. */
    private fun recordingLogger(sink: MutableList<String>): Logger = object : Logger {
        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.ERROR
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level == LogLevel.ERROR) sink.add(message.toString())
        }
    }

    @Test
    fun `the no re-register disarm clears every bit that arming READ set`() {
        val watched = 2000
        val fake = FakeEpollSyscallOps().apply {
            scriptEpollCreateFd(fd = 1000)
            scriptEventfdCreateFd(fd = 1001)
            // One readable event for the watched fd, then a fatal errno so
            // loop() leaves its while(running) via the break.
            scriptWaitOk(watched to EPOLLIN)
            scriptWaitFailure(EBADF)
        }
        val loop = EpollEventLoop(logger, syscallOps = fake)

        val listener = DecliningListener()
        // Registering off the loop thread funnels the epoll_ctl onto the loop,
        // so nothing reaches the fake until loop() drains its task queue. One
        // loop() call therefore carries the whole sequence: arm, dispatch the
        // scripted event, then disarm because the listener did not re-arm.
        loop.registerCallback(watched, Interest.READ, listener)
        loop.loop()

        assertEquals(1, listener.readyCount, "the listener should have been dispatched once")

        val forFd = fake.ctlCalls.filter { it.fd == watched }
        assertTrue(forFd.size >= 2, "expected an arm and a disarm for fd=$watched, got $forFd")

        val armed = forFd.first().events
        assertTrue(
            armed and EPOLLRDHUP != 0 && armed and EPOLLIN != 0,
            "precondition: READ should arm EPOLLIN|EPOLLRDHUP, got 0x${armed.toString(16)}",
        )

        // Every call after the arm is a take-back, so none of them may still
        // carry a READ bit. Asserted over all of them rather than over the last
        // one: a DEL records events = 0 in the fake, so reading the mask off it
        // would assert the fake's placeholder rather than anything the engine
        // computed, and would go quiet the moment a DEL is issued for any other
        // reason.
        val afterArm = forFd.drop(1)
        val stillArmed = afterArm.filter {
            it.op != FakeEpollSyscallOps.CtlOp.DEL && (it.events and EPOLL_READ_ARMED) != 0
        }
        assertTrue(
            stillArmed.isEmpty(),
            "a take-back for fd=$watched still carries a READ bit: $stillArmed. " +
                "loopBody cannot dispatch an EPOLLRDHUP-only event, so a level-triggered epoll " +
                "returns this fd on every iteration once the peer closes, with no handler to run.",
        )

        // The last interest going away must take the fd out of the interest
        // list, not just empty its mask: EPOLLERR / EPOLLHUP are reported
        // whether or not they were asked for, so a registered fd with 0 events
        // still returns from every epoll_wait once the peer resets.
        assertEquals(
            FakeEpollSyscallOps.CtlOp.DEL,
            forFd.last().op,
            "the disarm left fd=$watched registered (op=${forFd.last().op}); an empty mask does not " +
                "stop EPOLLERR / EPOLLHUP being reported, so a peer reset spins the loop on a fd " +
                "whose one-shot callback is already consumed.",
        )
    }

    private companion object {
        /** What `registerCallback` arms for [Interest.READ]; the disarm must take all of it back. */
        private val EPOLL_READ_ARMED = EPOLLIN or EPOLLRDHUP
    }
}
