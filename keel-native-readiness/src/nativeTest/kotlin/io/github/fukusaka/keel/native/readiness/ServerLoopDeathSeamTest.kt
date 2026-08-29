@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.dup
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a Channel-mode server says once its accept loop has stopped.
 *
 * An engine close stops the loops and leaves the servers to their owner, so a
 * server nobody closed keeps its descriptor bound over a boss that will never
 * accept again: a peer's connect completes into a backlog nobody drains. The
 * pipelined server in this same engine already answers for that -- its
 * `isActive` reads the loop as well as its own flag -- and this one did not,
 * so the two siblings disagreed about the same state two files apart.
 *
 * The descriptor is deliberately still open in these cases. Releasing it is
 * this server's own `close()`, not the engine's, and the answer is worth
 * having precisely while the descriptor is still there: that is the window in
 * which a caller can be told not to keep asking.
 *
 * The three cases pin the three answers this property could have been written
 * against: the loop's state alone, this server's flag alone, and quiescence
 * rather than the moment polling stops. Each is bounded by [withTimeout]
 * (wall-clock: `runBlocking` builder, per the project's timeout rule) -- the
 * first two drive a teardown, and the third must not, which its own comment
 * gives the reason for.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ServerLoopDeathSeamTest : AbstractReadinessEventLoopFixture() {

    /** One fake worker, which is the loop every accepted connection would be given. */
    private class FakeWorkerGroup(loop: FakeLoop) : AbstractReadinessEventLoopGroup<FakeLoop>(arrayOf(loop))

    /** A real socket fd, so `close()` releases something the kernel knows about. */
    private fun newListenerFd(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setUp" }
        return fd
    }

    /** Whether [fd] still names an open descriptor, without keeping the probe. */
    private fun stillOpen(fd: Int): Boolean {
        val probe = dup(fd)
        if (probe < 0) return false
        close(probe)
        return true
    }

    private fun server(boss: FakeLoop, worker: FakeLoop, fd: Int, port: Int) =
        ReadinessStreamServer(
            serverFd = fd,
            bossLoop = boss,
            workerGroup = FakeWorkerGroup(worker),
            localAddress = InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), port),
            bindConfig = BindConfig(),
            logger = boss.logger,
            nativeSocket = FakeNativeSocket(),
            nativeSocketOps = FakeNativeSocketOps(),
        )

    @Test
    fun `a server whose accept loop stopped is not listening`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val boss = owned(FakeLoop())
            val worker = owned(FakeLoop())
            val fd = newListenerFd()
            val server = server(boss, worker, fd, TEST_PORT)
            try {
                assertTrue(server.isActive, "open, with a loop that still polls")

                boss.closeAsStoppedLoop()

                assertFalse(
                    server.isActive,
                    "a boss that has stopped polling will never accept again, whatever this " +
                        "server was told -- and its pipelined sibling already answers this way",
                )
                assertTrue(
                    stillOpen(fd),
                    "while the descriptor stays open, which is what makes the answer worth having",
                )
            } finally {
                server.close()
            }
            assertFalse(stillOpen(fd), "and this server's own close is what releases it")
        }
    }

    @Test
    fun `a server closed on a live loop is not listening either`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other direction, or the case above passes for an answer that
            // reads only the loop and forgets what this server was told.
            val boss = owned(FakeLoop())
            val worker = owned(FakeLoop())
            val fd = newListenerFd()
            val server = server(boss, worker, fd, TEST_PORT + 1)
            try {
                server.close()

                assertFalse(server.isActive, "closed is closed, on a loop that is still polling")
                assertFalse(stillOpen(fd), "and the descriptor goes back")
            } finally {
                if (stillOpen(fd)) close(fd)
            }
        }
    }

    @Test
    fun `a server whose loop has finished but not yet swept is not listening`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The third answer this could have been written against, and the
            // one the two cases above do not tell apart: a loop publishes
            // "finished" when it stops polling and "quiescent" only after its
            // final drain and stop sweep have run. Between them the sweep's
            // own work runs -- participants told, waiters cancelled, and the
            // coroutines those resume -- so a server reading quiescence would
            // answer "listening" throughout, which is the window the loop's
            // own documentation warns a parking caller about. Reading
            // `isFinishing` is what closes it, and only this case says so:
            // measured, an implementation reading `isStopped` passes the other
            // two.
            val boss = owned(FakeLoop(onLoopThread = false, runDispatchedInline = false))
            val worker = owned(FakeLoop())
            val fd = newListenerFd()
            try {
                val server = server(boss, worker, fd, TEST_PORT + 2)
                assertTrue(server.isActive, "still polling")

                boss.stageFinishedNotQuiescent()

                assertFalse(
                    server.isActive,
                    "a loop that has stopped polling will not accept again, whether or not its " +
                        "sweep has finished running",
                )
            } finally {
                // Not through the server: its close hands the release to the
                // loop and waits for quiescence this case deliberately never
                // publishes, so the wait is the one thing that must not run.
                close(fd)
            }
        }
    }

    private companion object {
        /** Never bound: no accept completes in these cases. */
        const val TEST_PORT = 18301
    }
}
