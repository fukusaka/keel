package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.AF_INET
import platform.posix.EMFILE
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
 * What a group whose construction fails part way must not keep.
 *
 * The loops before the one that failed are *fully built* — each holding a
 * kqueue fd, a wakeup pipe, native scratch and an allocator child that only its
 * own `close()` returns. The array being filled is discarded along with the
 * constructor that threw, and the group reference never reaches a caller, so
 * nothing else can ever close them. An engine asks for one loop per worker, so
 * an application retrying a failed engine loses a group's worth per attempt.
 *
 * `EMFILE` on the fourth loop is the condition this is for: a process at its
 * descriptor limit, which is exactly when the earlier loops' descriptors matter
 * most.
 *
 * Real descriptors rather than fabricated numbers, for the reason the sibling
 * construction suite gives: whether `close(2)` reached one is not answerable
 * about a number nobody opened, and closing it would shut whatever this process
 * has open there.
 *
 * **What this file does not cover**: the rollback in `start()`. Reaching it
 * needs `pthread_create` to fail, and that call is deliberately outside the
 * [KqueueSyscallOps] seam — its own KDoc says so. That rollback is a guard, and
 * no test in the tree makes it fire.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEventLoopGroupRollbackTest {

    private val logger = NoopLoggerFactory.logger("KqueueEventLoopGroupRollbackTest")

    @Test
    fun `a group whose fourth loop cannot be built releases the three before it`() {
        // Three loops' worth of descriptors: one kqueue fd and a wakeup pair
        // each, consumed in that order by the fake's FIFO scripts.
        withRealFds(LOOPS_BUILT * FDS_PER_LOOP) { fds ->
            val tracker = TrackingAllocator()
            val fake = FakeKqueueSyscallOps().apply {
                repeat(LOOPS_BUILT) { loop ->
                    scriptKqueueCreateFd(fds[loop * FDS_PER_LOOP])
                    scriptMakePipeFds(
                        readFd = fds[loop * FDS_PER_LOOP + 1],
                        writeFd = fds[loop * FDS_PER_LOOP + 2],
                    )
                }
                scriptKqueueCreateFailure(EMFILE)
            }

            val failure = assertFailsWith<IllegalStateException> {
                KqueueEventLoopGroup(
                    size = LOOPS_BUILT + 1,
                    logger = logger,
                    allocator = tracker,
                    syscallOps = fake,
                )
            }

            assertTrue(
                failure.message!!.contains("kqueue()"),
                "the caller should be told which stage failed, got: ${failure.message}",
            )
            fds.forEachIndexed { i, fd ->
                assertEquals(
                    -1,
                    fcntl(fd, F_GETFD),
                    "the group kept descriptor $i (fd=$fd) of a loop it had already built; " +
                        "nothing can close it once the constructor has thrown",
                )
            }
            assertEquals(
                LOOPS_BUILT + 1,
                tracker.totalCloseCount(),
                "and every allocator child handed out is closed: the three the group rolls back, " +
                    "plus the one the failing loop was given — its argument is evaluated before the " +
                    "constructor that throws, and that constructor's own unwind returns it",
            )
        }
    }

    /**
     * Opens [count] real descriptors, runs [block] with them, and closes them so
     * a failing assertion does not also leak out of this suite. A second
     * `close(2)` on one the group already released is the `EBADF` this ignores;
     * nothing here opens a descriptor between the throw and this `finally`, so a
     * released number has not been handed out again by the time it is closed.
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

    private companion object {
        /** Loops the fake lets through before refusing the next one. */
        const val LOOPS_BUILT = 3

        /** A kqueue fd and the two ends of a wakeup pipe. */
        const val FDS_PER_LOOP = 3
    }
}
