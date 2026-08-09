package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketOption
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.HandoffOutcome
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.EBADF
import platform.posix.ECONNABORTED
import platform.posix.EMFILE
import platform.posix.F_GETFD
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.dup
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-level tests for `accept`-path branches on the epoll engine:
 * [EpollStreamServer.accept] (suspend-based) and
 * [EpollPipelinedStreamServer.onAcceptable] (callback-based).
 *
 * Complements [EpollEngineLifecycleSeamTest] (connect + bind) by
 * exercising the third engine-state transition — accept. Both paths
 * are scripted through [FakeNativeSocket.enqueueAccept] (the
 * `AcceptResult` discriminator) and [FakeNativeSocketOps] (the
 * `setNonBlocking` / `getRemoteAddress` / `getLocalAddress` /
 * `setSocketOption` chain that runs on `Accepted`).
 *
 * ## What this file does NOT cover
 *
 * - **`accept` `WouldBlock` suspend path (coroutine-based)** —
 *   `EpollStreamServer.accept()`'s `WouldBlock` branch registers the server
 *   fd on the boss event loop's real epoll and suspends the
 *   continuation; resuming requires the real socket to become readable.
 *   Exercised by `EpollEngineTest` integration tests.
 * - **`onAcceptable` `Accepted` branch, past the hand-off** — the flow
 *   calls `transport.readEnabled = true`, which arms a real worker-loop
 *   read on the accepted fd. A *fake* fd fails there with EBADF, which
 *   is why most of this file stops before the hand-off; the tests that
 *   need to go through it pass a real descriptor. Integration tests
 *   cover the full accept-to-first-byte flow.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollAcceptSeamTest {

    private fun newEngine(
        fakeSocket: FakeNativeSocket = FakeNativeSocket(),
        fakeOps: FakeNativeSocketOps = FakeNativeSocketOps(),
        threads: Int = 1,
    ): EpollEngine = EpollEngine(
        config = IoEngineConfig(threads = threads),
        nativeSocket = fakeSocket,
        nativeSocketOps = fakeOps,
    )

    /**
     * Creates a real but unbound `socket(AF_INET, SOCK_STREAM, 0)` fd
     * so `bindListener` / `epoll_ctl(ADD, serverFd)` succeed. Mirrors
     * the sentinel pattern used by `bind` happy-path tests (PR #338).
     */
    private fun newSentinelFd(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "failed to create sentinel socket" }
        return fd
    }

    // --- EpollStreamServer.accept: Failed branches ---

    @Test
    fun `accept Failed ECONNABORTED throws with errno message`() = runBlocking {
        withTimeout(15.seconds) {
            val sentinelFd = newSentinelFd()
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18080)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Failed(ECONNABORTED))
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
            }
            val engine = newEngine(fakeSocket, fakeOps)
            try {
                val server = engine.bind(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                )
                val ex = assertFailsWith<IllegalStateException> { server.accept() }
                assertTrue(
                    ex.message!!.contains("Software caused connection abort") ||
                        ex.message!!.contains("connection abort"),
                    "expected ECONNABORTED errno text, got: ${ex.message}",
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
    fun `accept Failed EMFILE throws with errno message`() = runBlocking {
        withTimeout(15.seconds) {
            val sentinelFd = newSentinelFd()
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18081)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Failed(EMFILE))
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
            }
            val engine = newEngine(fakeSocket, fakeOps)
            try {
                val server = engine.bind(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                )
                val ex = assertFailsWith<IllegalStateException> { server.accept() }
                assertTrue(
                    ex.message!!.contains("Too many open files") ||
                        ex.message!!.contains("open files"),
                    "expected EMFILE errno text, got: ${ex.message}",
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

    // --- EpollStreamServer.accept: Accepted branch (happy path + setSocketOption chain) ---

    @Test
    fun `accept Accepted returns channel with setNonBlocking plus scripted addresses`() = runBlocking {
        withTimeout(15.seconds) {
            val sentinelFd = newSentinelFd()
            val clientFd = 4242
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18082)
            val scriptedClientRemote = InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 5555)
            val scriptedClientLocal = InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), 18082)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(clientFd))
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
                enqueueRemoteAddress(clientFd, scriptedClientRemote)
                enqueueLocalAddress(clientFd, scriptedClientLocal)
            }
            val engine = newEngine(fakeSocket, fakeOps)
            try {
                val server = engine.bind(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                )
                val channel = server.accept()
                assertEquals(scriptedClientRemote, channel.remoteAddress)
                assertEquals(scriptedClientLocal, channel.localAddress)
                assertEquals(1, fakeSocket.acceptCalls)
                assertEquals(listOf(clientFd), fakeOps.nonBlockingFds)
                assertEquals(1, fakeOps.getRemoteAddressCalls)
                // getLocalAddress is called twice: once for the bind listener fd
                // (in bindInet) and once for the accepted client fd.
                assertEquals(2, fakeOps.getLocalAddressCalls)
                channel.close()
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
    fun `accept Accepted applies childSocketOptions before returning channel`() = runBlocking {
        withTimeout(15.seconds) {
            val sentinelFd = newSentinelFd()
            val clientFd = 4243
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18083)
            val scriptedClientRemote = InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 5556)
            val scriptedClientLocal = InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), 18083)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(clientFd))
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
                enqueueRemoteAddress(clientFd, scriptedClientRemote)
                enqueueLocalAddress(clientFd, scriptedClientLocal)
            }
            val engine = newEngine(fakeSocket, fakeOps)
            try {
                val server = engine.bind(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(
                        childSocketOptions = SocketOptions(tcpNoDelay = true, keepAlive = true),
                    ),
                )
                val channel = server.accept()
                assertEquals(
                    listOf(
                        clientFd to SocketOption.TcpNoDelay(true),
                        clientFd to SocketOption.KeepAlive(true),
                    ),
                    fakeOps.appliedOptions,
                )
                channel.close()
                server.close()
            } catch (t: Throwable) {
                close(sentinelFd)
                throw t
            } finally {
                engine.close()
            }
        }
    }

    // --- EpollPipelinedStreamServer.onAcceptable: Failed / WouldBlock ---
    //
    // bindPipeline returns an EpollPipelinedStreamServer; we cast and call
    // the internal onAcceptable() directly to drive the accept loop branches
    // deterministically (no real event delivery). The sentinel fd is needed
    // so start() and the re-arm epoll_ctl(ADD) calls succeed — the fd is
    // never actually listened on, so no real connections interfere.

    @Test
    fun `onAcceptable Failed logs and re-arms without dispatching`() = runBlocking {
        withTimeout(15.seconds) {
            val sentinelFd = newSentinelFd()
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18084)
            val fakeSocket = FakeNativeSocket().apply {
                // First call on onAcceptable() returns Failed; after Failed the
                // engine re-arms without calling accept again.
                enqueueAccept(sentinelFd, AcceptResult.Failed(ECONNABORTED))
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
            }
            val engine = newEngine(fakeSocket, fakeOps)
            try {
                val server = engine.bindPipeline(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                ) { /* no-op initializer */ }
                val pipelined = server as EpollPipelinedStreamServer
                pipelined.onAcceptable()
                assertEquals(1, fakeSocket.acceptCalls)
                // No Accepted → no setNonBlocking / address reads.
                assertTrue(fakeOps.nonBlockingFds.isEmpty())
                assertEquals(0, fakeOps.getRemoteAddressCalls)
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
    fun `a socket that cannot be prepared is closed and the accept loop carries on`() = runBlocking {
        withTimeout(15.seconds) {
            // `setNonBlocking` is `check(...)` over `fcntl` in production, so one
            // accepted socket whose descriptor cannot be made non-blocking
            // throws on the accept loop's own thread -- out of the loop, out of the readiness dispatch, off a
            // pthread entry with nothing above it, ending the process. The
            // listener and every other connection are blameless.
            val sentinelFd = newSentinelFd()
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18186)
            // A real descriptor: the failure path closes it, and whether it did
            // is half of what this asserts.
            val doomedFd = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(doomedFd >= 0, "could not open a socket to be accepted")
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(doomedFd))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
                setNonBlockingThrowsOnce = IllegalStateException("fcntl(F_SETFL, O_NONBLOCK) failed: boom")
            }
            val engine = newEngine(fakeSocket, fakeOps)
            try {
                val server = engine.bindPipeline(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                ) { /* no-op initializer */ }
                val pipelined = server as EpollPipelinedStreamServer

                pipelined.onAcceptable()

                assertEquals(
                    2,
                    fakeSocket.acceptCalls,
                    "the loop must go round again rather than unwind: the next peer is not at fault",
                )
                assertEquals(
                    -1,
                    fcntl(doomedFd, F_GETFD),
                    "setup did not finish, so no transport owns that descriptor and this must release it",
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
    fun `a wrapped worker index does not turn every accept into a dropped one`() = runBlocking {
        withTimeout(15.seconds) {
            // `workerIndex++ % size` goes negative after Int.MAX_VALUE accepts,
            // and a negative index throws out of `at()`. The per-socket guard
            // catches it, so the loop survives -- and closes and drops the
            // connection. The counter keeps incrementing, so from then on one
            // accept in `size` lands on a usable index and the rest are
            // dropped, one warning each, for as long as the server runs: the
            // listener looks healthy and serves a fraction. The counter is
            // wound here rather than reached, because reaching it means two
            // billion accepts.
            val sentinelFd = newSentinelFd()
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18188)
            val acceptedFd = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(acceptedFd >= 0, "could not open a socket to be accepted")
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(acceptedFd))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
            }
            // Two workers, because `n % 1` is 0 for every n -- a single-worker
            // group cannot be indexed wrongly, so it cannot show this at all.
            val engine = newEngine(fakeSocket, fakeOps, threads = 2)
            try {
                val server = engine.bindPipeline(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                ) { /* no-op initializer */ }
                val pipelined = server as EpollPipelinedStreamServer
                // Any value the wrap passes through; -1 makes an unmasked
                // modulo negative for every group size above one.
                pipelined.setWorkerIndexForTest(-1)

                pipelined.onAcceptable()

                assertTrue(
                    fcntl(acceptedFd, F_GETFD) >= 0,
                    "the connection is handed to a worker, not dropped for the counter's sake",
                )
                // Nothing else will: the worker transport holds it, and with an
                // empty pipeline the loop-stop notification does not close.
                close(acceptedFd)
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
    fun `an accept handed to a stopped worker releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // A worker's queue outlives the worker: `dispatch` takes a task
            // whatever state the loop is in, and after the final drain nothing
            // drains it again. An accepted descriptor handed over that way was
            // neither served nor released -- it stayed open until the process
            // exited, while the peer's `connect` had already succeeded and it
            // waited on a socket nobody would ever read.
            //
            // The boss loop is left running: this is one half-stopped engine,
            // not a closed one, which is the state a worker that broke out of
            // its own loop leaves behind.
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = EpollEventLoop(warns)
            val workerGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val acceptedFd = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(acceptedFd >= 0, "could not open a socket to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18189)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(acceptedFd))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = EpollPipelinedStreamServer(
                listeners = listOf(
                    EpollPipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
                ),
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                logger = warns,
                pipelineInitializer = { /* no-op initializer */ },
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            // Both loops start inside the `try`: a pthread each, and an assert
            // failing before the `finally` is reached would leave them running
            // for the rest of the suite -- one process runs all of it.
            try {
                bossLoop.start()
                workerGroup.start()
                // Deliberately not armed on the boss loop. An unbound listen
                // socket reports readiness on it at once and goes on doing so,
                // so an armed listener runs this accept loop from the boss
                // thread too -- and the two threads then race for the one
                // scripted `Accepted`, which the boss can take while the worker
                // is still alive. The accept below is driven directly instead,
                // which is what this seam does everywhere else. (`acceptLoop`
                // does arm the listener on its way out, so the boss picks the
                // storm up from there -- by then the script is drained.)
                //
                // Joined and quiescent: nothing will drain this worker's queue
                // again.
                workerGroup.close()

                server.onAcceptable()

                val probe = dup(acceptedFd)
                val probeErrno = errno
                if (probe >= 0) close(probe)
                assertEquals(
                    -1,
                    probe,
                    "a worker that will never run this accept must not be left holding the descriptor",
                )
                assertEquals(EBADF, probeErrno, "closed, not fd-table exhaustion: the probe must fail with EBADF")
                assertEquals(
                    2,
                    fakeSocket.acceptCalls,
                    "dropping one connection must not unwind the loop: the peers queued behind it are not at fault",
                )
                assertEquals(
                    1,
                    warns.messages.count { "fd=$acceptedFd" in it && "has stopped" in it },
                    "the drop is reported once, naming the descriptor: ${warns.messages}",
                )
            } finally {
                // Before the closes below, which can be handed this number: on a
                // failing run production left it open. `dup` only says the
                // number is open, not that it is still this socket -- nothing
                // here can tell the difference -- but between the assertions
                // and this line the test opens nothing, and the boss loop is
                // parked, so there is no recycling to be caught out by.
                val leftOpen = dup(acceptedFd)
                if (leftOpen >= 0) {
                    close(leftOpen)
                    close(acceptedFd)
                }
                server.close()
                bossLoop.close()
                workerGroup.close()
            }
        }
    }

    @Test
    fun `a stopped worker costs the accept callback one wait rather than one per connection`() {
        // The budget bounds a single hand-off. This loop makes as many as the
        // backlog holds, so without carrying the verdict across iterations a
        // worker stuck between "finished polling" and "quiet" would cost the
        // full wait *each* -- a listen backlog of 128 turns a 100ms bound into
        // 12.8s inside one readiness callback, with the boss loop serving no
        // other listener and draining no task for the whole of it. The bound
        // has to belong to the callback.
        val fresh = EpollPipelinedStreamServer.DropTally()
        assertEquals(
            EpollPipelinedStreamServer.STOPPING_WORKER_WAIT_MICROS,
            fresh.budget(),
            "the first hand-off of a callback pays the wait",
        )

        val afterDrop = fresh.record(7, HandoffOutcome.FELL_BACK)
        assertEquals(
            EpollPipelinedStreamServer.STOPPING_WORKER_WAIT_MICROS,
            afterDrop.budget(),
            "a worker already quiet was not waited for, so nothing has been learned about waiting",
        )

        val afterGivingUp = afterDrop.record(8, HandoffOutcome.FELL_BACK_AFTER_EXPIRY)
        assertEquals(
            0L,
            afterGivingUp.budget(),
            "having waited one out and given up, this callback must not pay the wait again",
        )
        assertEquals(
            0L,
            afterGivingUp.record(9, HandoffOutcome.HANDED_TO_LOOP).budget(),
            "and a live worker in between does not reset it",
        )
    }

    @Test
    fun `several accepts dropped by one stopped worker are reported once`() = runBlocking {
        withTimeout(15.seconds) {
            // One line per dropped connection turns a worker that stays down
            // into a log flood at the accept rate, which is what this callback
            // produces for as long as peers keep arriving.
            val warns = RecordingLogger(LogLevel.WARN)
            val bossLoop = EpollEventLoop(warns)
            val workerGroup = EpollEventLoopGroup(1, warns, DefaultAllocator)
            val sentinelFd = newSentinelFd()
            val first = socket(AF_INET, SOCK_STREAM, 0)
            val second = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(first >= 0 && second >= 0, "could not open sockets to be accepted")
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18190)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.Accepted(first))
                enqueueAccept(sentinelFd, AcceptResult.Accepted(second))
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val server = EpollPipelinedStreamServer(
                listeners = listOf(
                    EpollPipelinedStreamServer.Listener(sentinelFd, scriptedLocal, BindConfig()),
                ),
                bossLoop = bossLoop,
                workerGroup = workerGroup,
                logger = warns,
                pipelineInitializer = { /* no-op initializer */ },
                nativeSocket = fakeSocket,
                nativeSocketOps = FakeNativeSocketOps(),
            )
            try {
                bossLoop.start()
                workerGroup.start()
                workerGroup.close()

                server.onAcceptable()

                assertEquals(
                    1,
                    warns.messages.count { "has stopped" in it },
                    "two drops, one line: ${warns.messages}",
                )
                assertTrue(
                    warns.messages.any { "2 accepted connection(s)" in it },
                    "and it must say how many, not just name one: ${warns.messages}",
                )
                for (fd in listOf(first, second)) {
                    val probe = dup(fd)
                    if (probe >= 0) close(probe)
                    assertEquals(-1, probe, "every dropped descriptor is released, not only the first")
                }
            } finally {
                for (fd in listOf(first, second)) {
                    val leftOpen = dup(fd)
                    if (leftOpen >= 0) {
                        close(leftOpen)
                        close(fd)
                    }
                }
                server.close()
                bossLoop.close()
                workerGroup.close()
            }
        }
    }

    @Test
    fun `onAcceptable WouldBlock re-arms without side effects`() = runBlocking {
        withTimeout(15.seconds) {
            val sentinelFd = newSentinelFd()
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18085)
            val fakeSocket = FakeNativeSocket().apply {
                enqueueAccept(sentinelFd, AcceptResult.WouldBlock)
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
            }
            val engine = newEngine(fakeSocket, fakeOps)
            try {
                val server = engine.bindPipeline(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                ) { /* no-op */ }
                val pipelined = server as EpollPipelinedStreamServer
                pipelined.onAcceptable()
                assertEquals(1, fakeSocket.acceptCalls)
                assertTrue(fakeOps.nonBlockingFds.isEmpty())
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
    fun `an accept loop that throws leaves the listener armed`() = runBlocking {
        withTimeout(15.seconds) {
            // `arm()` is reached from `start()` and from the two branches that
            // end the loop normally, so a throw on the way out left this
            // listener with no registration and nothing that would give it one
            // back -- the readiness dispatch found no listener for the key and
            // took the interest away. The server went on reporting itself
            // active and never accepted again. Silent, where the crash this
            // guard replaced at least said something.
            val sentinelFd = newSentinelFd()
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18187)
            val fakeSocket = FakeNativeSocket().apply {
                acceptThrowsOnce = IllegalStateException("the accept loop failed")
            }
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
            }
            val engine = newEngine(fakeSocket, fakeOps)
            try {
                val server = engine.bindPipeline(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                ) { /* no-op initializer */ }
                val pipelined = server as EpollPipelinedStreamServer

                pipelined.dispatchAcceptReadiness()

                assertTrue(
                    pipelined.isFirstListenerArmed(),
                    "a listener that is still open stays armed, whatever the loop threw",
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
}
