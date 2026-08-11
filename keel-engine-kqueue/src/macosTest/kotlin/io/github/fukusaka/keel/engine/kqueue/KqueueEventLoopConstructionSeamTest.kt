package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.AF_INET
import platform.posix.EBADF
import platform.posix.EMFILE
import platform.posix.EPERM
import platform.posix.F_GETFD
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.fcntl
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What a `KqueueEventLoop` constructor that fails must not keep.
 *
 * The loop takes a kqueue fd and a wakeup pipe, and [KqueueEventLoop.close]
 * is the only thing that gives them back. A constructor that throws hands out
 * no reference, so `close` is unreachable for the rest of the process: every
 * descriptor still open at that point is open until it exits. The engine
 * builds one loop per worker, and an application that retries a failed
 * `IoEngine` loses them per attempt.
 *
 * Four cases over three stages -- the wakeup fds are made non-blocking one at
 * a time, and each end gets its own -- asking the same question of every
 * descriptor the constructor had taken by then. The stage that creates the
 * kqueue fd is not here: it fails before there is anything to give back, and
 * the sibling seam suite already drives it. Real descriptors are
 * used rather than fabricated numbers: the point is whether `close(2)`
 * reached them, which a made-up number cannot answer — and closing one would
 * shut whatever this process happens to have open there.
 *
 * **What this file does not cover.** Two stages fail only if `fcntl` does, on
 * a descriptor the kernel has just returned, and neither is behind the
 * [KqueueSyscallOps] seam: the `FD_CLOEXEC` calls inside
 * `PosixKqueueSyscallOps.kqueueCreate` and `makePipe`. They release what they
 * took and report it through the errno their contract promises, but nothing
 * here reaches them — they are guards, and no test in the tree makes them
 * fire.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEventLoopConstructionSeamTest {

    private val logger = NoopLoggerFactory.logger("KqueueEventLoopConstructionSeamTest")

    @Test
    fun `the loop releases every descriptor when the wakeup read end cannot be made non-blocking`() {
        withRealFds(3) { (kqFd, readFd, writeFd) ->
            val fake = FakeKqueueSyscallOps().apply {
                scriptKqueueCreateFd(kqFd)
                scriptMakePipeFds(readFd = readFd, writeFd = writeFd)
                scriptSetNonBlockingFailure(EPERM)
            }

            val failure = assertFailsWith<IllegalStateException> {
                KqueueEventLoop(logger, syscallOps = fake)
            }

            assertTrue(
                failure.message!!.contains("O_NONBLOCK"),
                "the caller should be told which stage failed, got: ${failure.message}",
            )
            assertEquals(1, fake.setNonBlockingCalls, "the second end should not have been attempted")
            assertClosed(kqFd, "kqueue fd")
            assertClosed(readFd, "wakeup read end")
            assertClosed(writeFd, "wakeup write end")
        }
    }

    @Test
    fun `the loop releases every descriptor when the wakeup write end cannot be made non-blocking`() {
        // The other end of the same stage: the first call has already
        // succeeded, so the failure arrives with one more thing done and the
        // same three descriptors owed back.
        withRealFds(3) { (kqFd, readFd, writeFd) ->
            val fake = FakeKqueueSyscallOps().apply {
                scriptKqueueCreateFd(kqFd)
                scriptMakePipeFds(readFd = readFd, writeFd = writeFd)
                scriptSetNonBlockingSuccess()
                scriptSetNonBlockingFailure(EPERM)
            }

            assertFailsWith<IllegalStateException> {
                KqueueEventLoop(logger, syscallOps = fake)
            }

            assertEquals(2, fake.setNonBlockingCalls)
            assertClosed(kqFd, "kqueue fd")
            assertClosed(readFd, "wakeup read end")
            assertClosed(writeFd, "wakeup write end")
        }
    }

    @Test
    fun `the loop releases every descriptor when the wakeup fd cannot be registered`() {
        // This stage already released all three. It is asserted here because
        // nothing asserted it: the sibling seam test drives the same branch
        // with fabricated numbers, so it can read the message but not the
        // releases.
        withRealFds(3) { (kqFd, readFd, writeFd) ->
            val fake = FakeKqueueSyscallOps().apply {
                scriptKqueueCreateFd(kqFd)
                scriptMakePipeFds(readFd = readFd, writeFd = writeFd)
                scriptAddFilterResult(EBADF)
            }

            assertFailsWith<IllegalStateException> {
                KqueueEventLoop(logger, syscallOps = fake)
            }

            assertClosed(kqFd, "kqueue fd")
            assertClosed(readFd, "wakeup read end")
            assertClosed(writeFd, "wakeup write end")
        }
    }

    @Test
    fun `the loop releases the kqueue fd when the wakeup pipe cannot be created`() {
        // Only one descriptor exists to give back here: `makePipe` reports a
        // failure having left `fds` untouched, or having released both ends
        // itself.
        withRealFds(1) { (kqFd) ->
            val fake = FakeKqueueSyscallOps().apply {
                scriptKqueueCreateFd(kqFd)
                scriptMakePipeFailure(EMFILE)
            }

            assertFailsWith<IllegalStateException> {
                KqueueEventLoop(logger, syscallOps = fake)
            }

            assertClosed(kqFd, "kqueue fd")
        }
    }

    /**
     * Opens [count] real descriptors, runs [block] with them, and closes any
     * the constructor left behind so a failing assertion does not also leak
     * out of this suite. A second `close(2)` on one already released is the
     * `EBADF` this ignores.
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
            fds.forEach { close(it) }
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
