@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.native.posix.ConnectResult
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What a connect tells its caller when the join did not take.
 *
 * Nothing reached these two guards before: the connect paths raise a failure
 * whose message comes from the loop's own answer, and a revert to a fixed
 * string — one that names the sweep whatever happened — passed every suite.
 * The wording helper is pinned in [JoinRefusalTest]; these cases pin that the
 * connect sites still ask it.
 *
 * Both drive a teardown, so both are bounded by [withTimeout] (wall-clock:
 * `runBlocking` builder, per the project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class EngineConnectJoinRefusalSeamTest : AbstractReadinessEventLoopFixture() {

    private class FakeWorkerGroup(loop: FakeLoop) : AbstractReadinessEventLoopGroup<FakeLoop>(arrayOf(loop))

    /**
     * The abstract engine over fake loops, which is what makes the guard
     * reachable.
     *
     * Every case closes it: the loops hold native gather scratch from
     * construction, and only their close gives it back.
     */
    private class TestEngine(
        boss: FakeLoop,
        worker: FakeLoop,
        ops: FakeNativeSocketOps,
    ) : AbstractReadinessEngine("TestEngine", IoEngineConfig(), FakeNativeSocket(), ops) {
        override val bossLoop: AbstractReadinessEventLoop = boss
        override val workerGroup: AbstractReadinessEventLoopGroup<*> = FakeWorkerGroup(worker)
    }

    /** A real socket fd, so the teardown's release closes something real. */
    private fun newFd(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setup" }
        return fd
    }

    private fun stillOpen(fd: Int): Boolean {
        val probe = dup(fd)
        if (probe < 0) return false
        close(probe)
        return true
    }

    /** Connected outright, and handing out [fd], so no await stands between us and the join. */
    private fun ops(fd: Int) = FakeNativeSocketOps().apply {
        nextCreatedFd = fd
        defaultConnect = ConnectResult.Connected
    }

    @Test
    fun `a connect onto a swept worker names the stop`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val boss = FakeLoop()
            val worker = FakeLoop(onLoopThread = true)
            worker.closeAsStoppedLoop()
            val fd = newFd()
            val engine = TestEngine(boss, worker, ops(fd))
            try {
                val failed = assertFailsWith<IllegalStateException> {
                    engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), TEST_PORT))
                }

                val message = checkNotNull(failed.message)
                assertTrue(
                    joinRefusalReason(JoinRefusal.LOOP_STOPPED) in message,
                    "the caller is told which of the two happened, got: $message",
                )
                assertFalse(stillOpen(fd), "and the connection it could not build gives its descriptor back")
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `a connect whose arm the kernel refused names the arm`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other direction, or the case above passes for a site that
            // names the sweep whatever happened -- which is what it did before.
            val boss = FakeLoop()
            val worker = FakeLoop(onLoopThread = true)
            worker.failArmCallback = true
            val fd = newFd()
            val engine = TestEngine(boss, worker, ops(fd))
            try {
                val failed = assertFailsWith<IllegalStateException> {
                    engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), TEST_PORT))
                }

                val message = checkNotNull(failed.message)
                assertTrue(
                    joinRefusalReason(JoinRefusal.ARM_REFUSED) in message,
                    "a running loop refused the arm, and saying it stopped would send a reader after the wrong " +
                        "thing, got: $message",
                )
                assertFalse(stillOpen(fd), "and the descriptor goes back here too")
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `a unix connect onto a swept worker names the stop`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The sibling site, which has its own copy of the raise.
            val boss = FakeLoop()
            val worker = FakeLoop(onLoopThread = true)
            worker.closeAsStoppedLoop()
            val fd = newFd()
            val engine = TestEngine(boss, worker, ops(fd))
            try {
                val failed = assertFailsWith<IllegalStateException> {
                    engine.connect(UnixSocketAddress("/tmp/keel-test-not-bound.sock"))
                }

                val message = checkNotNull(failed.message)
                assertTrue(
                    joinRefusalReason(JoinRefusal.LOOP_STOPPED) in message,
                    "the unix path answers the same way, got: $message",
                )
                assertFalse(stillOpen(fd), "and gives its descriptor back")
            } finally {
                engine.close()
            }
        }
    }

    private companion object {
        /** Never dialled: the fake reports the connect complete. */
        const val TEST_PORT = 18293
    }
}
