@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
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
 * What a Channel-mode accept does with each way a join can be refused.
 *
 * This is the one construction site where the two differ in more than wording.
 * `AcceptLoop` rethrows [CancellationException] and ends; anything else it logs
 * before backing off and retrying. A swept worker earns the first — nothing it
 * accepts afterwards could be served — and a refused arm must not, because the
 * loop is running and serving everyone else, so ending accept over one
 * connection would take a healthy server off the air.
 *
 * The mapping itself is pinned in [JoinRefusalTest]. What these cases pin is
 * that this site still asks it: a raise written back to one type for both
 * causes passes every test that only knows the mapping.
 *
 * Both cases drive a teardown, so both are bounded by [withTimeout]
 * (wall-clock: `runBlocking` builder, per the project's timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class ServerJoinRefusalSeamTest : AbstractReadinessEventLoopFixture() {

    /** One fake worker, which is the loop every accepted connection is given. */
    private class FakeWorkerGroup(loop: FakeLoop) : AbstractReadinessEventLoopGroup<FakeLoop>(arrayOf(loop))

    /** A real socket fd, so the release goes through a real `close`. */
    private fun newFd(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setup" }
        return fd
    }

    /** Whether [fd] still names an open descriptor, without keeping the probe. */
    private fun stillOpen(fd: Int): Boolean {
        val probe = dup(fd)
        if (probe < 0) return false
        close(probe)
        return true
    }

    private fun server(boss: FakeLoop, worker: FakeLoop, serverFd: Int, clientFd: Int) =
        ReadinessStreamServer(
            serverFd = serverFd,
            bossLoop = boss,
            workerGroup = FakeWorkerGroup(worker),
            localAddress = InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), TEST_PORT),
            bindConfig = BindConfig(),
            logger = boss.logger,
            nativeSocket = FakeNativeSocket().apply { enqueueAccept(serverFd, AcceptResult.Accepted(clientFd)) },
            nativeSocketOps = FakeNativeSocketOps(),
        )

    @Test
    fun `an accept whose worker refused the arm fails that connection alone`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val boss = FakeLoop()
            // On the loop, which is what makes the arm answer inline: an accept
            // that attaches from anywhere else has its arm queued and hears
            // about the refusal afterwards, through the transport.
            val worker = FakeLoop(onLoopThread = true)
            worker.failArmCallback = true
            val serverFd = newFd()
            val clientFd = newFd()
            val server = server(boss, worker, serverFd, clientFd)

            val failure = assertFailsWith<IllegalStateException> { server.accept() }

            assertTrue(
                failure !is CancellationException,
                "a running worker keeps serving everyone else, so this must not end the accept " +
                    "loop -- AcceptLoop rethrows only a cancellation: $failure",
            )
            assertFalse(stillOpen(clientFd), "and the connection nobody holds gives its descriptor back")
            server.close()
        }
    }

    @Test
    fun `an accept whose worker swept ends the accept loop`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other direction, or the case above passes for a site that
            // raises an ordinary failure whatever happened.
            val boss = FakeLoop()
            val worker = FakeLoop(onLoopThread = true)
            worker.closeAsStoppedLoop()
            val serverFd = newFd()
            val clientFd = newFd()
            val server = server(boss, worker, serverFd, clientFd)

            assertFailsWith<CancellationException> { server.accept() }

            assertFalse(stillOpen(clientFd), "and this one gives its descriptor back too")
            server.close()
        }
    }

    private companion object {
        /** Never bound: the fake socket answers every accept. */
        const val TEST_PORT = 18271
    }
}
