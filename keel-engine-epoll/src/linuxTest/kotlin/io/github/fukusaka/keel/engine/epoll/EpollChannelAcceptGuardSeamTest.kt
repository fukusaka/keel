@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.native.readiness.ReadinessStreamServer
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.dup
import platform.posix.socket
import platform.posix.usleep
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * What a Channel-mode accept does when a step between the descriptor
 * arriving and the caller receiving a channel fails.
 *
 * Split from [EpollAcceptFailureSeamTest], which covers the pipelined
 * server's hand-off to a worker. These cover the other server: preparing
 * the socket, a worker that swept before the attach, the caller's own
 * connection initialiser, and a release that throws while the guard is
 * dropping the connection.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollChannelAcceptGuardSeamTest {

    /**
     * Whether [fd] still names an open descriptor, without keeping the
     * duplicate it asks with.
     */
    private fun stillOpen(fd: Int): Boolean {
        val probe = dup(fd)
        if (probe < 0) return false
        close(probe)
        return true
    }

    /**
     * Creates a real but unbound `socket(AF_INET, SOCK_STREAM, 0)` fd so the
     * arm calls succeed. Mirrors the sibling suite.
     */
    private fun newSentinelFd(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "failed to create sentinel socket" }
        return fd
    }

    @Test
    fun `a Channel-mode accept whose socket cannot be prepared releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // The three calls before the transport exists all throw on a failed
            // syscall, and a peer that resets between `accept()` returning and
            // the address query is enough to get one. The throw reaches the
            // accept loop, which logs, backs off and retries -- so a descriptor
            // left here is one per accept until the table is full.
            val sentinelFd = newSentinelFd()
            val doomed = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(doomed >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18193)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(doomed))
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
                setNonBlockingThrowsOnce = IllegalStateException("fcntl(F_SETFL, O_NONBLOCK) failed: boom")
            }
            val warns = RecordingLogger(LogLevel.WARN)
            val engine = EpollEngine(
                config = IoEngineConfig(threads = 1, loggerFactory = { warns }),
                nativeSocket = fakeSocket,
                nativeSocketOps = fakeOps,
            )
            try {
                val server = engine.bind(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                )

                assertFailsWith<IllegalStateException> { server.accept() }

                assertFalse(
                    stillOpen(doomed),
                    "setup did not finish, so nothing owns that descriptor and this must release it",
                )
                // The release is only half of it, and the raised exception does
                // not name the descriptor -- this is what lets an operator tie
                // the failure to the connection it lost.
                assertEquals(
                    1,
                    warns.messages.count { "preparing an accepted socket failed" in it && "fd=$doomed" in it },
                    "the drop is reported, naming the descriptor: ${warns.messages}",
                )
                server.close()
            } catch (t: Throwable) {
                close(sentinelFd)
                throw t
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `a Channel-mode accept whose bind config initialiser throws releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // `BindConfig.initializeConnection` is an `open fun`, so this is the
            // caller's code running between the attach and the return. A throw
            // there leaves the connection joined to the loop, holding its
            // descriptor and never read, and the channel it would be closed
            // through has not been returned to anyone yet.
            val warns = RecordingLogger(LogLevel.WARN)
            val sentinelFd = newSentinelFd()
            val doomed = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(doomed >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18196)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(doomed))
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
            }
            val engine = EpollEngine(
                config = IoEngineConfig(threads = 1, loggerFactory = { warns }),
                nativeSocket = fakeSocket,
                nativeSocketOps = fakeOps,
            )
            try {
                val server = engine.bind(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    FailingInitializerBindConfig,
                )

                val raised = assertFailsWith<InjectedFault> { server.accept() }
                // Named rather than typed: the release runs a teardown that
                // re-raises its own stage failures, and the caller must be told
                // what it asked about rather than what the cleanup hit.
                assertEquals(
                    "the bind config initialiser for this connection failed",
                    raised.message,
                    "the caller waiting on accept() is told the initialiser's own failure",
                )

                // The transport exists, so the release is its teardown -- handed
                // to the worker loop rather than run here, unlike the
                // setup-window guard's raw close. Waited for rather than read
                // straight away.
                val deadline = TimeSource.Monotonic.markNow()
                while (deadline.elapsedNow() < CLOSE_BUDGET && stillOpen(doomed)) {
                    usleep(CLOSE_POLL_MICROS)
                }
                assertFalse(
                    stillOpen(doomed),
                    "a connection nobody holds and nothing will read must not keep its descriptor",
                )
                assertEquals(
                    1,
                    warns.messages.count { "initialising an accepted connection failed" in it && "fd=$doomed" in it },
                    "and the drop is reported, naming the descriptor: ${warns.messages}",
                )
                server.close()
            } catch (t: Throwable) {
                close(sentinelFd)
                throw t
            } finally {
                engine.close()
            }
        }
    }

    /** A bind config whose connection initialiser always fails. */
    private object FailingInitializerBindConfig : BindConfig() {
        override fun initializeConnection(channel: PipelinedChannel): Unit =
            throw InjectedFault("the bind config initialiser for this connection failed")
    }

    @Test
    fun `a Channel-mode accept whose worker swept before the attach releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // The worker's ledgers close between the accept and the attach, so
            // `joinLoop` refuses and the connection is joined to nothing. Every
            // Channel-mode guard releases and raises; what is particular here is
            // that the transport exists but joined nothing, so its teardown runs
            // inline on this thread rather than being handed to a live loop --
            // which is why the descriptor is gone by the assertion with no wait.
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = EpollEventLoop(warns)
            val sweptGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val doomed = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(doomed >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18197)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(doomed))
            }
            val server = ReadinessStreamServer(
                serverFd = sentinelFd,
                bossLoop = bossLoop,
                workerGroup = sweptGroup,
                localAddress = scriptedLocal,
                bindConfig = BindConfig(),
                logger = warns,
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            try {
                // Started and closed: the sweep runs, so the ledgers are shut
                // and every later join is refused. The same state the sibling
                // hand-off tests build for a stopped worker.
                sweptGroup.start()
                sweptGroup.close()

                assertFailsWith<CancellationException> { server.accept() }

                assertFalse(
                    stillOpen(doomed),
                    "a connection joined to nothing must not be left holding its descriptor",
                )
                // Pins which branch answered: the raised cancellation alone
                // cannot say, since the registration path raises the same type.
                assertEquals(
                    1,
                    warns.messages.count { "could not join its EventLoop" in it && "fd=$doomed" in it },
                    "the drop is reported from the accept side, naming the descriptor: ${warns.messages}",
                )
                // The raise says only that the join did not take -- a refused
                // arm ends the same way -- so the loop's own warning is the
                // only thing that names which of the two it was. Pinned here
                // because that is where a reader of this failure has to go.
                assertTrue(
                    warns.messages.any { "EventLoop stopped — refusing" in it },
                    "the loop names the sweep it was, not an arm the kernel refused: ${warns.messages}",
                )
                server.close()
            } catch (t: Throwable) {
                close(sentinelFd)
                throw t
            } finally {
                val leftOpen = dup(doomed)
                if (leftOpen >= 0) {
                    close(leftOpen)
                    close(doomed)
                }
                bossLoop.close()
                sweptGroup.close()
            }
        }
    }

    @Test
    fun `a swept-worker drop whose own release throws reports that too`() = runBlocking {
        withTimeout(15.seconds) {
            // The release here is a teardown that runs inline -- the worker is
            // quiescent, so there is no loop to hand it to -- and a teardown
            // re-raises what its stages hit. Attaching that to the cause is not
            // enough on this path: the cause is a CancellationException, which
            // the coroutine machinery reports to nobody, so without a line of
            // its own a failed teardown would go unrecorded entirely.
            //
            // Reached by handing the accept a descriptor number that cannot
            // be open, so the teardown's own `close(2)` fails and reports --
            // through the loop's logger, which throws for that one message.
            // Nothing else on the path produces it.
            //
            // A number well past any table rather than one just closed: a
            // freshly closed number is the next one the kernel hands out, so
            // anything opening a descriptor in this window would take it and
            // the teardown's close would succeed against a stranger's socket.
            // One process runs the whole suite.
            val warns = ThrowingCloseLogger()
            val bossLoop = EpollEventLoop(warns)
            val sweptGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val alreadyClosed = UNALLOCATABLE_FD
            assertFalse(stillOpen(alreadyClosed), "premise: the accepted number must not name a descriptor")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18198)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(alreadyClosed))
            }
            val server = ReadinessStreamServer(
                serverFd = sentinelFd,
                bossLoop = bossLoop,
                workerGroup = sweptGroup,
                localAddress = scriptedLocal,
                bindConfig = BindConfig(),
                logger = warns,
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            try {
                sweptGroup.start()
                sweptGroup.close()

                assertFailsWith<CancellationException> { server.accept() }

                // The seam reached a throwing release. Without this the
                // assertion below would hold on a build where nothing threw.
                assertTrue(
                    warns.threwOnClose,
                    "the teardown's close must have failed for this test to mean anything",
                )
                assertEquals(
                    1,
                    warns.messages.count { "releasing a dropped accepted connection failed as well" in it },
                    "a release that threw is reported rather than only attached: ${warns.messages}",
                )
                server.close()
            } catch (t: Throwable) {
                close(sentinelFd)
                throw t
            } finally {
                bossLoop.close()
                sweptGroup.close()
            }
        }
    }

    /**
     * Records like [RecordingLogger], but throws from the one message the
     * transport teardown emits when its `close(2)` fails.
     *
     * That line is unstaged in the teardown, so throwing from it is how a
     * teardown failure reaches the accept path's release guard. Every other
     * message is recorded, which is what lets the assertions read the guard's
     * own line back.
     */
    private class ThrowingCloseLogger : Logger {

        private val sink = AtomicReference<List<String>>(emptyList())

        private val refused = AtomicInt(0)

        /** Whether the teardown's close report was seen, and refused. */
        val threwOnClose: Boolean get() = refused.value == 1

        val messages: List<String> get() = sink.value

        override fun isLoggable(level: LogLevel): Boolean = level == LogLevel.WARN

        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            if (level != LogLevel.WARN) return
            val text = message.toString()
            if ("transport teardown" in text) {
                refused.value = 1
                throw InjectedFault("the teardown could not release the descriptor")
            }
            while (true) {
                val current = sink.value
                if (sink.compareAndSet(current, current + text)) return
            }
        }
    }

    private companion object {
        /** Poll interval while waiting for a worker thread to release a descriptor. */
        const val CLOSE_POLL_MICROS: UInt = 1_000u

        /** Wall-clock bound on that wait; generous, since it only has to exclude a hang. */
        val CLOSE_BUDGET = 10.seconds

        /**
         * A descriptor number past any table this process will reach, so
         * `close(2)` on it fails without touching anybody's socket.
         */
        const val UNALLOCATABLE_FD: Int = 1_000_000
    }
}
