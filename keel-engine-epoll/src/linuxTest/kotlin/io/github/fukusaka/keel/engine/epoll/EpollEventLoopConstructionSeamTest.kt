package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.AF_INET
import platform.posix.EBADF
import platform.posix.EMFILE
import platform.posix.F_GETFD
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.fcntl
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * What an `EpollEventLoop` constructor that fails must not keep.
 *
 * The loop takes an epoll fd and a wakeup eventfd, and [EpollEventLoop.close]
 * is the only thing that gives them back. A constructor that throws hands out
 * no reference, so `close` is unreachable for the rest of the process: every
 * descriptor still open at that point is open until it exits.
 *
 * Both descriptor stages here released what they held before the loop's
 * construction was staged, and both still do — what nothing asserted was the
 * releasing. A third case covers the allocator child, which the unwind did not
 * give back at all until this branch.
 * The sibling seam suite drives the same two branches with fabricated numbers,
 * so it can read the message and the call counts but not whether `close(2)`
 * happened. Real descriptors answer that; a made-up number cannot, and closing
 * one would shut whatever this process has open there.
 *
 * The kqueue loop has a third stage — its wakeup pipe is made non-blocking by
 * an op whose contract is to throw — and no counterpart here: `epoll_create1`
 * carries `EPOLL_CLOEXEC` and the eventfd `EFD_CLOEXEC | EFD_NONBLOCK`, so
 * there is no post-`fcntl` step to fail.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollEventLoopConstructionSeamTest {

    private val logger = NoopLoggerFactory.logger("EpollEventLoopConstructionSeamTest")

    @Test
    fun `the loop releases the epoll fd when the wakeup eventfd cannot be created`() {
        withRealFds(1) { (epFd) ->
            val fake = FakeEpollSyscallOps().apply {
                scriptEpollCreateFd(epFd)
                scriptEventfdCreateFailure(EMFILE)
            }

            assertFailsWith<IllegalStateException> {
                EpollEventLoop(logger, syscallOps = fake)
            }

            assertClosed(epFd, "epoll fd")
        }
    }

    @Test
    fun `the loop releases both descriptors when the wakeup fd cannot be registered`() {
        withRealFds(2) { (epFd, wakeupFd) ->
            val fake = FakeEpollSyscallOps().apply {
                scriptEpollCreateFd(epFd)
                scriptEventfdCreateFd(wakeupFd)
                scriptAddResult(EBADF)
            }

            assertFailsWith<IllegalStateException> {
                EpollEventLoop(logger, syscallOps = fake)
            }

            assertClosed(epFd, "epoll fd")
            assertClosed(wakeupFd, "wakeup eventfd")
        }
    }

    @Test
    fun `the loop closes the allocator child it was handed when construction fails`() {
        // The fourth thing the constructor takes, and the only one not made of
        // descriptors or native memory. The loop is what closes it -- the
        // teardown path ends by doing so -- and the parent's cascade does not
        // stand in for that here, because an engine whose construction failed
        // is discarded and nobody closes the parent either.
        withRealFds(1) { (epFd) ->
            val tracker = TrackingAllocator()
            val child = tracker.createChild()
            val fake = FakeEpollSyscallOps().apply {
                scriptEpollCreateFd(epFd)
                scriptEventfdCreateFailure(EMFILE)
            }

            assertFailsWith<IllegalStateException> {
                EpollEventLoop(logger, allocator = child, syscallOps = fake)
            }

            assertEquals(
                1,
                tracker.totalCloseCount(),
                "the loop kept the allocator child it was handed; nothing can close it once init has thrown",
            )
        }
    }

    /**
     * Opens [count] real descriptors, runs [block] with them, and closes any
     * the constructor left behind so a failing assertion does not also leak out
     * of this suite. Only those still open: closing a number the constructor
     * already released would close whatever the kernel has since handed out
     * under it — the hazard these cases exist to detect.
     */
    private fun withRealFds(count: Int, block: (List<Int>) -> Unit) {
        val fds = List(count) {
            val fd = socket(AF_INET, SOCK_STREAM, 0)
            check(fd >= 0) { "socket() failed while preparing the test" }
            fd
        }
        try {
            block(fds)
        } finally {
            fds.forEach { if (fcntl(it, F_GETFD) != -1) close(it) }
        }
    }

    private fun assertClosed(fd: Int, what: String) {
        assertEquals(
            -1,
            fcntl(fd, F_GETFD),
            "the constructor kept the $what (fd=$fd); nothing can close it once init has thrown",
        )
    }
}
