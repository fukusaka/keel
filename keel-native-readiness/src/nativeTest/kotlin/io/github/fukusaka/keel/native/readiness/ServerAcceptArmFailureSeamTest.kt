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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.dup
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what a refused accept arm costs the pipelined server, and who can see it.
 *
 * When `epoll_ctl` / `kevent(EV_ADD)` refuses the accept arm, the arm was the
 * only thing that would ever drive `accept()` again — so the listener ends:
 * its port is released (a peer is refused promptly instead of parking in a
 * backlog nobody drains), its address leaves [PipelinedStreamServer.activeLocalAddresses],
 * and when the last listener goes the server goes with it. Before this, the
 * withdrawal was an ERROR log the server never saw: it went on reporting
 * `isActive` and simply never accepted again, holding every bound port
 * hostage — the outcome its own accept guard calls worse than the crash.
 *
 * Every case drives loop-dispatched work, so every case is bounded by
 * [withTimeout] (wall-clock: `runBlocking` builder, per the project's
 * timeout rule).
 */
@OptIn(ExperimentalForeignApi::class)
internal class ServerAcceptArmFailureSeamTest : AbstractReadinessEventLoopFixture() {

    /** The group the server never hands to in these cases; one inert fake loop. */
    private class FakeWorkerGroup(loop: FakeLoop) : AbstractReadinessEventLoopGroup<FakeLoop>(arrayOf(loop))

    /** Whether [fd] still names an open descriptor, without keeping the probe. */
    private fun stillOpen(fd: Int): Boolean {
        val probe = dup(fd)
        if (probe < 0) return false
        close(probe)
        return true
    }

    /** A real socket fd, so releasing a listener goes through a real `close`. */
    private fun newListenerFd(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed in test setup" }
        return fd
    }

    private fun address(port: Int) = InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), port)

    private fun server(
        boss: FakeLoop,
        group: FakeWorkerGroup,
        fake: FakeNativeSocket,
        vararg listeners: ReadinessPipelinedStreamServer.Listener,
    ) = ReadinessPipelinedStreamServer(
        listeners = listeners.toList(),
        bossLoop = boss,
        workerGroup = group,
        logger = boss.logger,
        pipelineInitializer = { /* never reached: no accept completes in these cases */ },
        nativeSocket = fake,
        nativeSocketOps = FakeNativeSocketOps(),
    )

    @Test
    fun `a failed accept re-arm ends its listener and the sibling keeps accepting`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val boss = FakeLoop()
            val worker = FakeLoop()
            val group = FakeWorkerGroup(worker)
            val fd1 = newListenerFd()
            val fd2 = newListenerFd()
            val addr1 = address(18201)
            val addr2 = address(18202)
            val fake = FakeNativeSocket()
            try {
                val server = server(
                    boss,
                    group,
                    fake,
                    ReadinessPipelinedStreamServer.Listener(fd1, addr1, BindConfig()),
                    ReadinessPipelinedStreamServer.Listener(fd2, addr2, BindConfig()),
                )
                server.start()
                assertEquals(listOf(addr1, addr2), server.activeLocalAddresses)

                // The drive: accept drains to WouldBlock, and the re-arm the
                // exit issues is the one the kernel refuses — for this
                // listener only, which is the whole point of per-listener fate.
                fake.enqueueAccept(fd1, AcceptResult.WouldBlock)
                boss.failArmCallbackForFd = fd1
                server.onAcceptable()

                assertEquals(listOf(addr2), server.activeLocalAddresses)
                assertTrue(server.isActive, "one live listener is a listening server")
                assertFalse(stillOpen(fd1), "the dead listener's port is released, not held hostage")
                assertTrue(stillOpen(fd2), "the sibling did nothing wrong")
                assertTrue(
                    boss.hasCallbackRegistration(fd2, Interest.READ),
                    "the sibling's arm is untouched",
                )
                assertTrue(
                    boss.errors.any { "accept arm failed" in it },
                    "the reason is reported, naming the listener: ${boss.errors}",
                )
                fake.assertAllConsumed()
                server.close()
                assertFalse(stillOpen(fd2), "close releases what the failure did not")
            } finally {
                boss.close()
                worker.close()
            }
        }
    }

    @Test
    fun `the last listener's failed arm closes the server`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            val boss = FakeLoop()
            val worker = FakeLoop()
            val group = FakeWorkerGroup(worker)
            val fd = newListenerFd()
            val addr = address(18203)
            val fake = FakeNativeSocket()
            try {
                val server = server(
                    boss,
                    group,
                    fake,
                    ReadinessPipelinedStreamServer.Listener(fd, addr, BindConfig()),
                )
                server.start()
                fake.enqueueAccept(fd, AcceptResult.WouldBlock)
                boss.failArmCallback = true
                server.onAcceptable()

                assertFalse(server.isActive, "a server with zero listeners must not report listening")
                assertTrue(
                    server.activeLocalAddresses.isEmpty(),
                    "no address is claimed accepting: ${server.activeLocalAddresses}",
                )
                assertFalse(stillOpen(fd), "the port is released with the listener")
                fake.assertAllConsumed()
            } finally {
                boss.close()
                worker.close()
            }
        }
    }

    @Test
    fun `an arm the stopping boss will never fire ends the listener`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // start() hands its arms to a boss that looks live; the boss goes
            // down before the task runs, and the final drain executes it —
            // where the ledgers may still take the arm and the sweep clears
            // it in silence, or refuse it with the null the sweep's answer
            // channel owns. Either way no readiness event is ever coming, so
            // an armed-looking listener over a port nobody will accept on
            // must end instead. Found by independent review of this branch's
            // first shape.
            val boss = FakeLoop(onLoopThread = false, runDispatchedInline = false)
            val worker = FakeLoop()
            val group = FakeWorkerGroup(worker)
            val fd = newListenerFd()
            val fake = FakeNativeSocket()
            try {
                val server = server(
                    boss,
                    group,
                    fake,
                    ReadinessPipelinedStreamServer.Listener(fd, address(18205), BindConfig()),
                )
                server.start()
                boss.closeAsStoppedLoop()

                assertFalse(server.isActive, "an arm nobody will fire is not a listening server")
                assertTrue(
                    server.activeLocalAddresses.isEmpty(),
                    "no address is claimed accepting: ${server.activeLocalAddresses}",
                )
                assertFalse(stillOpen(fd), "the port is released, not held hostage")
            } finally {
                worker.close()
            }
        }
    }

    @Test
    fun `starting onto a stopped boss closes the server instead of holding the ports`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The boss stopped between bind and start — every path that ends
            // the loop reaches here, engine close as much as a fatal poll
            // errno. Arming is impossible, so the bound ports must not
            // outlive the loop that would have accepted on them.
            val boss = FakeLoop()
            boss.closeAsStoppedLoop()
            val worker = FakeLoop()
            val group = FakeWorkerGroup(worker)
            val fd = newListenerFd()
            val fake = FakeNativeSocket()
            try {
                val server = server(
                    boss,
                    group,
                    fake,
                    ReadinessPipelinedStreamServer.Listener(fd, address(18204), BindConfig()),
                )
                server.start()

                assertFalse(server.isActive)
                assertTrue(
                    server.activeLocalAddresses.isEmpty(),
                    "no address is claimed accepting: ${server.activeLocalAddresses}",
                )
                assertFalse(stillOpen(fd), "a port nobody can accept on is released")
            } finally {
                worker.close()
            }
        }
    }
}
