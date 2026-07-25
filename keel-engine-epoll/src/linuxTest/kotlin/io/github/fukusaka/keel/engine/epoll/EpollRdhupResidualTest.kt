package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.FdReadyListener
import io.github.fukusaka.keel.native.posix.Interest
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
    private object DecliningListener : FdReadyListener {
        var readyCount = 0
        override fun onReady(interest: Interest) {
            readyCount++
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

        DecliningListener.readyCount = 0
        // Registering off the loop thread funnels the epoll_ctl onto the loop,
        // so nothing reaches the fake until loop() drains its task queue. One
        // loop() call therefore carries the whole sequence: arm, dispatch the
        // scripted event, then disarm because the listener did not re-arm.
        loop.registerCallback(watched, Interest.READ, DecliningListener)
        loop.loop()

        assertEquals(1, DecliningListener.readyCount, "the listener should have been dispatched once")

        val forFd = fake.ctlCalls.filter { it.fd == watched }
        assertTrue(forFd.size >= 2, "expected an arm and a disarm for fd=$watched, got $forFd")

        val armed = forFd.first().events
        assertTrue(
            armed and EPOLLRDHUP != 0 && armed and EPOLLIN != 0,
            "precondition: READ should arm EPOLLIN|EPOLLRDHUP, got 0x${armed.toString(16)}",
        )

        val remaining = forFd.last().events
        assertEquals(
            0,
            remaining and EPOLLRDHUP,
            "EPOLLRDHUP is still armed after the disarm (mask 0x${remaining.toString(16)}). " +
                "loopBody cannot dispatch an EPOLLRDHUP-only event, so a level-triggered epoll " +
                "returns this fd on every iteration once the peer closes, with no handler to run.",
        )
        assertEquals(
            0,
            remaining and EPOLLIN,
            "EPOLLIN is still armed after the disarm (mask 0x${remaining.toString(16)}).",
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
}
