package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketOption
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.native.posix.AcceptResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.ECONNABORTED
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
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-level tests for `accept`-path branches on the kqueue engine:
 * [KqueueStreamServer.accept] (suspend-based) and
 * [KqueuePipelinedStreamServer.onAcceptable] (callback-based).
 *
 * Complements [KqueueEngineLifecycleSeamTest] (connect + bind) by
 * exercising the third engine-state transition — accept. Both paths
 * are scripted through [FakeNativeSocket.enqueueAccept] (the
 * `AcceptResult` discriminator) and [FakeNativeSocketOps] (the
 * `setNonBlocking` / `getRemoteAddress` / `getLocalAddress` /
 * `setSocketOption` chain that runs on `Accepted`).
 *
 * ## What this file does NOT cover
 *
 * - **`accept` `WouldBlock` suspend path (coroutine-based)** —
 *   `KqueueStreamServer.accept()`'s `WouldBlock` branch registers the server
 *   fd on the boss event loop's real kqueue and suspends the
 *   continuation; resuming requires the real socket to become readable.
 *   Exercised by `KqueueEngineTest` integration tests.
 * - **`onAcceptable` `Accepted` branch (callback-based)** — after
 *   `setNonBlocking` + `getAddr` chain, the flow calls
 *   `transport.readEnabled = true` which arms a real worker-loop read
 *   on the (fake) accepted fd and fails with EBADF. Integration tests
 *   cover the full accept-to-first-byte flow.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueAcceptSeamTest {

    private fun newEngine(
        fakeSocket: FakeNativeSocket = FakeNativeSocket(),
        fakeOps: FakeNativeSocketOps = FakeNativeSocketOps(),
    ): KqueueEngine = KqueueEngine(
        config = IoEngineConfig(threads = 1),
        nativeSocket = fakeSocket,
        nativeSocketOps = fakeOps,
    )

    /**
     * Creates a real but unbound `socket(AF_INET, SOCK_STREAM, 0)` fd
     * so `bindListener` / `kevent(EV_ADD, serverFd)` succeed. Mirrors
     * the sentinel pattern used by `bind` happy-path tests (PR #338).
     */
    private fun newSentinelFd(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "failed to create sentinel socket" }
        return fd
    }

    // --- KqueueStreamServer.accept: Failed branches ---

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

    // --- KqueueStreamServer.accept: Accepted branch (happy path + setSocketOption chain) ---

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

    // --- KqueuePipelinedStreamServer.onAcceptable: Failed / WouldBlock ---
    //
    // bindPipeline returns a KqueuePipelinedStreamServer; we cast and call
    // the internal onAcceptable() directly to drive the accept loop branches
    // deterministically (no real event delivery). The sentinel fd is needed
    // so start() and the re-arm kevent(EV_ADD) calls succeed — the fd is
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
                val pipelined = server as KqueuePipelinedStreamServer
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
            // accepted socket meeting a resource limit throws on the accept loop's own
            // thread -- out of the loop, out of the readiness dispatch, off a
            // pthread entry with nothing above it, ending the process. The
            // listener and every other connection are blameless.
            val sentinelFd = newSentinelFd()
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18086)
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
                val pipelined = server as KqueuePipelinedStreamServer

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
                val pipelined = server as KqueuePipelinedStreamServer
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
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18087)
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
                val pipelined = server as KqueuePipelinedStreamServer

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
