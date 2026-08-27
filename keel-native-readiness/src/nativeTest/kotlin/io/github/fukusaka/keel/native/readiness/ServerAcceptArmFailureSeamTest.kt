@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import io.github.fukusaka.keel.testing.InjectedFault
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
import kotlin.test.assertNotNull
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
    fun `a server whose accept loop stopped claims no address`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // An engine close stops the loops and leaves the servers to their
            // owner, so a server nobody closed keeps its listeners bound over
            // a boss that will never accept again: a peer's connect completes
            // into a backlog nobody drains, which is the state releasing a
            // port exists to avoid. Measured through a real engine by
            // independent review, where this server said every address was
            // still accepting while the Netty server, asked the same thing,
            // said none.
            val boss = FakeLoop()
            val worker = FakeLoop()
            val group = FakeWorkerGroup(worker)
            val fd = newListenerFd()
            val addr = address(18210)
            val fake = FakeNativeSocket()
            try {
                val server = server(
                    boss,
                    group,
                    fake,
                    ReadinessPipelinedStreamServer.Listener(fd, addr, BindConfig()),
                )
                server.start()
                assertEquals(listOf(addr), server.activeLocalAddresses, "armed and accepting")

                boss.closeAsStoppedLoop()

                assertFalse(server.isActive, "a server whose loop stopped polling is not listening")
                assertTrue(
                    server.activeLocalAddresses.isEmpty(),
                    "and claims no address as accepting: ${server.activeLocalAddresses}",
                )
                assertTrue(
                    stillOpen(fd),
                    "while the port stays bound — that is what makes the answer worth having",
                )
                server.close()
                assertFalse(stillOpen(fd), "and this server's own close is what releases it")
            } finally {
                boss.close()
                worker.close()
            }
        }
    }

    @Test
    fun `a bind that outwaits its budget gives the ports back itself`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The boss is finished but not quiescent — its drain and sweep are
            // still running application code — so the hand-off waits, and the
            // budget is what ends that wait. What the budget buys is only real
            // if this frame then releases the ports itself: routing through
            // close() would wait out the very quiescence it just declined to
            // wait for. Measured by independent review, which found the first
            // shape waiting the full teardown despite the budget.
            //
            // **The regression shows up as a hang, not a failure.** The wait
            // is a blocking `usleep` spin, which no `withTimeout` can cut
            // short, so a shape that waits again takes this case — and the
            // suite around it — past every budget rather than reporting one.
            // A run that stops here with nothing written is that shape.
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
                    ReadinessPipelinedStreamServer.Listener(fd, address(18209), BindConfig()),
                )
                boss.stageFinishedNotQuiescent()

                server.start()

                assertFalse(server.isActive, "the arms were never issued, so the server is not listening")
                assertTrue(
                    server.activeLocalAddresses.isEmpty(),
                    "no address is claimed accepting: ${server.activeLocalAddresses}",
                )
                assertFalse(stillOpen(fd), "and the port is back, released by this frame rather than a wait")
                assertTrue(
                    boss.errors.any { "did not finish stopping" in it },
                    "the give-up names the wait it ran out of, not a loop already gone: ${boss.errors}",
                )
                assertTrue(
                    boss.errors.none { "has stopped" in it },
                    "and does not claim the other timing: ${boss.errors}",
                )
            } finally {
                boss.close()
                worker.close()
            }
        }
    }

    @Test
    fun `an arm on a boss that died carries the reason it died`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The listener's end names the shutdown; what killed the loop is
            // the operator's actual question, and the transport's stopped-loop
            // answers have carried it all along. Found by independent review:
            // the cause was added without a test, so nothing said it arrived.
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
                    ReadinessPipelinedStreamServer.Listener(fd, address(18208), BindConfig()),
                )
                server.start()
                boss.stageLoopFault(InjectedFault("the poll this loop died on"))
                boss.closeAsStoppedLoop()

                assertFalse(server.isActive, "the arm the drain ran will never fire, so the listener ended")
                val reported = boss.logger.records.firstOrNull { "accept arm failed" in it.second }
                assertNotNull(reported, "the end is reported: ${boss.errors}")
                assertTrue(
                    reported.third?.cause is InjectedFault,
                    "and the reason the loop died rides along, got: ${reported.third?.cause}",
                )
            } finally {
                boss.close()
                worker.close()
            }
        }
    }

    @Test
    fun `a closed server claims no address before its listeners are marked`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // close() flips the server's own flag on the caller's thread and
            // marks the arms from a task on the boss loop. Between the two an
            // off-loop reader must not find every address alive on a server
            // that already turns readiness away — the engines that answer from
            // isActive alone are empty the moment they close, and this one
            // agrees. Found by independent review.
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
                    ReadinessPipelinedStreamServer.Listener(fd, address(18206), BindConfig()),
                )

                server.close()

                assertFalse(server.isActive, "the flag is the caller's, and it is already down")
                assertTrue(
                    server.activeLocalAddresses.isEmpty(),
                    "and nothing is claimed accepting while the marking is still queued: " +
                        "${server.activeLocalAddresses}",
                )
                boss.drainDispatched()
                assertTrue(server.activeLocalAddresses.isEmpty(), "still, once the task has run")
                assertFalse(stillOpen(fd), "which is when the port goes")
            } finally {
                boss.close()
                worker.close()
            }
        }
    }

    @Test
    fun `starting onto a stopped boss says why the server never listened`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The close is silent and start() raises nothing, so without this
            // line the caller holds a server that never listened and has
            // nowhere to read the reason — while the neighbouring timing (an
            // arm the final drain runs) reports at ERROR. Found by independent
            // review.
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
                    ReadinessPipelinedStreamServer.Listener(fd, address(18207), BindConfig()),
                )
                server.start()

                assertFalse(server.isActive)
                assertTrue(
                    boss.errors.any { "has stopped" in it && "closed this server unstarted" in it },
                    "the reason is reported as a loop already gone, naming the addresses: ${boss.errors}",
                )
                assertTrue(
                    boss.errors.none { "did not finish stopping" in it },
                    "and not as a wait that ran out: ${boss.errors}",
                )
            } finally {
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
                assertTrue(
                    boss.errors.any { "accept arm failed" in it },
                    "and the listener's end is reported: ${boss.errors}",
                )
            } finally {
                // The boss too, though the body closes it: its scratch is
                // released mid-body, so anything throwing before that line
                // would leave it. Idempotent, like every loop's close.
                boss.close()
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
