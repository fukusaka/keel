package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketOption
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.native.posix.ConnectResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import io.github.fukusaka.keel.native.readiness.ReadinessSuspendRegister
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.ECONNREFUSED
import platform.posix.ECONNRESET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Seam-level unit tests for `EpollEngine` lifecycle branches via
 * `FakeNativeSocketOps` injection. Covers the paths that the
 * `NativeSocketOps` cold-path seam was designed to make unit-testable:
 * `connect()` `ConnectResult.Failed` / `Connected`, `bind()` fd
 * propagation, and the address-read chain after Connected.
 *
 * Part of the project's two-layer seam + integration testing strategy
 * (this file covers the seam side).
 *
 * ## What this file does NOT cover
 *
 * - **`ConnectResult.InProgress` branch** — the engine suspends on
 *   `EPOLLOUT` via the real worker event loop, which requires a real
 *   fd to become writable. With a fake fd (e.g. 100) `epoll_ctl(ADD)`
 *   logs EBADF and the continuation never resumes. Exercised by
 *   `EpollEngineTest` integration tests against real loopback.
 * - **`bind` happy path** — covered here via a real socket fd as
 *   sentinel (see `bindInet / bindUnix happy path` tests). `bindListener`
 *   is scripted to return a `socket(AF_INET, SOCK_STREAM, 0)` fd so
 *   `epoll_ctl(ADD, serverFd)` on the boss loop succeeds; the engine
 *   then reads the scripted local address and constructs `ReadinessStreamServer`.
 *   Full accept flow (client → kernel → EPOLLIN → accept) is still
 *   integration-only.
 */
@OptIn(ExperimentalForeignApi::class)
class EpollEngineLifecycleSeamTest {

    private fun newEngine(
        fakeSocket: FakeNativeSocket = FakeNativeSocket(),
        fakeOps: FakeNativeSocketOps = FakeNativeSocketOps(),
        suspendRegisterOverride: ReadinessSuspendRegister? = null,
    ): EpollEngine = EpollEngine(
        config = IoEngineConfig(threads = 1),
        nativeSocket = fakeSocket,
        nativeSocketOps = fakeOps,
        suspendRegisterOverride = suspendRegisterOverride,
    )

    /** Immediate-resume fake: returns normally from `awaitWriteReady`. */
    private val immediateSuspendRegister = ReadinessSuspendRegister { _, _ ->
        // Deliberately empty: resume immediately.
    }

    // --- connect Inet: Failed branch ---

    @Test
    fun `connectInet Failed ECONNREFUSED throws with errno message`() = runBlocking {
        withTimeout(15.seconds) {
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenClientSocket(100)
                enqueueConnect(fd = 100, ConnectResult.Failed(ECONNREFUSED))
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val ex = assertFailsWith<IllegalStateException> {
                    engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), 12345))
                }
                assertTrue(
                    ex.message!!.contains("Connection refused"),
                    "message must include errno text, got: ${ex.message}",
                )
                assertEquals(1, fakeOps.openClientSocketCalls)
                assertEquals(1, fakeOps.connectCalls)
                // fd must have been passed to closeFdSafely on the Failed path
                // (cannot intercept directly as closeFdSafely uses the singleton;
                // fake tracks it only via openClientSocket / connect call counts).
                fakeOps.assertAllConsumed()
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `connectInet Failed ECONNRESET throws with errno message`() = runBlocking {
        withTimeout(15.seconds) {
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenClientSocket(101)
                enqueueConnect(fd = 101, ConnectResult.Failed(ECONNRESET))
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val ex = assertFailsWith<IllegalStateException> {
                    engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("10.0.0.1")), 8080))
                }
                assertTrue(ex.message!!.contains("Connection reset"), "got: ${ex.message}")
            } finally {
                engine.close()
            }
        }
    }

    // --- connect Inet: Connected branch ---

    @Test
    fun `connectInet Connected returns channel with scripted addresses`() = runBlocking {
        withTimeout(15.seconds) {
            val remote = InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80)
            val local = InetSocketAddress(Host.Ip(IpAddress.parse("5.6.7.8")), 49152)
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenClientSocket(200)
                enqueueConnect(fd = 200, ConnectResult.Connected)
                enqueueRemoteAddress(200, remote)
                enqueueLocalAddress(200, local)
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val channel = engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80))
                assertNotNull(channel)
                assertEquals(remote, channel.remoteAddress)
                assertEquals(local, channel.localAddress)
                assertEquals(1, fakeOps.openClientSocketCalls)
                assertEquals(1, fakeOps.connectCalls)
                assertEquals(1, fakeOps.getRemoteAddressCalls)
                assertEquals(1, fakeOps.getLocalAddressCalls)
                fakeOps.assertAllConsumed()
                channel.close()
            } finally {
                engine.close()
            }
        }
    }

    // --- connect Unix: Failed / Connected ---

    @Test
    fun `connectUnix Failed ECONNREFUSED throws with errno message`() = runBlocking {
        withTimeout(15.seconds) {
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenUnixClientSocket(300)
                enqueueConnectUnix(fd = 300, ConnectResult.Failed(ECONNREFUSED))
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val ex = assertFailsWith<IllegalStateException> {
                    engine.connect(UnixSocketAddress("/tmp/keel-fake.sock"))
                }
                assertTrue(ex.message!!.contains("Connection refused"), "got: ${ex.message}")
                assertEquals(1, fakeOps.openUnixClientSocketCalls)
                assertEquals(1, fakeOps.connectUnixCalls)
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `connectUnix Connected returns channel`() = runBlocking {
        withTimeout(15.seconds) {
            val addr = UnixSocketAddress("/tmp/keel-fake.sock")
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenUnixClientSocket(400)
                enqueueConnectUnix(fd = 400, ConnectResult.Connected)
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val channel = engine.connect(addr)
                assertNotNull(channel)
                assertEquals(addr, channel.remoteAddress)
                // UDS client connectNonBlocking does not call get*Address
                // (engine uses the bind target as remoteAddress directly).
                assertEquals(0, fakeOps.getRemoteAddressCalls)
                assertEquals(0, fakeOps.getLocalAddressCalls)
                fakeOps.assertAllConsumed()
                channel.close()
            } finally {
                engine.close()
            }
        }
    }

    // --- connect Inet: InProgress branch (suspend + SO_ERROR) ---

    @Test
    fun `connectInet InProgress then SO_ERROR non-zero throws`() = runBlocking {
        withTimeout(15.seconds) {
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenClientSocket(800)
                enqueueConnect(fd = 800, ConnectResult.InProgress)
                enqueueSocketError(800, platform.posix.ECONNREFUSED)
            }
            val engine = newEngine(fakeOps = fakeOps, suspendRegisterOverride = immediateSuspendRegister)
            try {
                val ex = assertFailsWith<IllegalStateException> {
                    engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80))
                }
                assertTrue(
                    ex.message!!.contains("Connection refused"),
                    "message must carry the errno from getSocketError, got: ${ex.message}",
                )
                // Confirm the InProgress path was actually exercised — if the
                // seam override hadn't been used, workerLoop.register would
                // have EBADF'd on fake fd 800 and the coroutine would hang.
                assertEquals(1, fakeOps.connectCalls)
                assertEquals(1, fakeOps.getSocketErrorCalls)
                fakeOps.assertAllConsumed()
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `connectInet InProgress then SO_ERROR zero returns channel`() = runBlocking {
        withTimeout(15.seconds) {
            val remote = InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80)
            val local = InetSocketAddress(Host.Ip(IpAddress.parse("5.6.7.8")), 49152)
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenClientSocket(801)
                enqueueConnect(fd = 801, ConnectResult.InProgress)
                enqueueSocketError(801, 0)
                enqueueRemoteAddress(801, remote)
                enqueueLocalAddress(801, local)
            }
            val engine = newEngine(fakeOps = fakeOps, suspendRegisterOverride = immediateSuspendRegister)
            try {
                val channel = engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80))
                assertNotNull(channel)
                assertEquals(remote, channel.remoteAddress)
                assertEquals(local, channel.localAddress)
                assertEquals(1, fakeOps.getSocketErrorCalls)
                fakeOps.assertAllConsumed()
                channel.close()
            } finally {
                engine.close()
            }
        }
    }

    // --- connect Unix: InProgress ---

    @Test
    fun `connectUnix InProgress then SO_ERROR non-zero throws`() = runBlocking {
        withTimeout(15.seconds) {
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenUnixClientSocket(802)
                enqueueConnectUnix(fd = 802, ConnectResult.InProgress)
                enqueueSocketError(802, platform.posix.ECONNREFUSED)
            }
            val engine = newEngine(fakeOps = fakeOps, suspendRegisterOverride = immediateSuspendRegister)
            try {
                val ex = assertFailsWith<IllegalStateException> {
                    engine.connect(UnixSocketAddress("/tmp/keel-fake.sock"))
                }
                assertTrue(ex.message!!.contains("Connection refused"), "got: ${ex.message}")
                assertEquals(1, fakeOps.connectUnixCalls)
                assertEquals(1, fakeOps.getSocketErrorCalls)
            } finally {
                engine.close()
            }
        }
    }

    // --- bind: failure branches (throw-from-create) ---

    @Test
    fun `bindInet surfaces bindListener EADDRINUSE`() = runBlocking {
        withTimeout(15.seconds) {
            val expected = IllegalStateException("bind() failed: Address already in use")
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListenerThrows(expected)
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val ex = assertFailsWith<IllegalStateException> {
                    engine.bind(InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 12345), BindConfig())
                }
                assertTrue(
                    ex.message!!.contains("Address already in use"),
                    "message must carry the original errno text, got: ${ex.message}",
                )
                assertEquals(1, fakeOps.bindListenerCalls)
                // No subsequent address read / server construction after throw.
                assertEquals(0, fakeOps.getLocalAddressCalls)
                fakeOps.assertAllConsumed()
            } finally {
                engine.close()
            }
        }
    }

    // --- Socket options application ---

    @Test
    fun `connect with ConnectConfig applies socket options before connectNonBlocking`() = runBlocking {
        withTimeout(15.seconds) {
            val options = SocketOptions(
                tcpNoDelay = true,
                keepAlive = true,
                receiveBufferSize = 65536,
                sendBufferSize = 131072,
            )
            val remote = InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80)
            val local = InetSocketAddress(Host.Ip(IpAddress.parse("5.6.7.8")), 49152)
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenClientSocket(700)
                enqueueConnect(fd = 700, ConnectResult.Connected)
                enqueueRemoteAddress(700, remote)
                enqueueLocalAddress(700, local)
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val channel = engine.connect(
                    InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80),
                    ConnectConfig(socketOptions = options),
                )
                assertNotNull(channel)
                // Options applied in declaration order: tcpNoDelay → keepAlive →
                // receiveBufferSize → sendBufferSize. All targeted the fd from
                // openClientSocket (700).
                assertEquals(
                    listOf(
                        700 to SocketOption.TcpNoDelay(true),
                        700 to SocketOption.KeepAlive(true),
                        700 to SocketOption.ReceiveBufferSize(65536),
                        700 to SocketOption.SendBufferSize(131072),
                    ),
                    fakeOps.appliedOptions,
                )
                assertEquals(4, fakeOps.setSocketOptionCalls)
                channel.close()
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `connect without ConnectConfig applies SocketOptions DEFAULT with TCP_NODELAY enabled`() = runBlocking {
        withTimeout(15.seconds) {
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenClientSocket(701)
                enqueueConnect(fd = 701, ConnectResult.Connected)
                enqueueRemoteAddress(701, InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80))
                enqueueLocalAddress(701, InetSocketAddress(Host.Ip(IpAddress.parse("5.6.7.8")), 1))
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80)).close()
                assertEquals(listOf(701 to SocketOption.TcpNoDelay(true)), fakeOps.appliedOptions)
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `connect with ConnectConfig partial options skips null properties`() = runBlocking {
        withTimeout(15.seconds) {
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueOpenClientSocket(702)
                enqueueConnect(fd = 702, ConnectResult.Connected)
                enqueueRemoteAddress(702, InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80))
                enqueueLocalAddress(702, InetSocketAddress(Host.Ip(IpAddress.parse("5.6.7.8")), 1))
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                engine.connect(
                    InetSocketAddress(Host.Ip(IpAddress.parse("1.2.3.4")), 80),
                    ConnectConfig(socketOptions = SocketOptions(tcpNoDelay = true)),
                ).close()
                // Only tcpNoDelay is set; other three properties are null.
                assertEquals(listOf(702 to SocketOption.TcpNoDelay(true)), fakeOps.appliedOptions)
                assertEquals(1, fakeOps.setSocketOptionCalls)
            } finally {
                engine.close()
            }
        }
    }

    // --- bind: happy path (real socket fd as sentinel) ---
    //
    // The engine's bind() registers serverFd with the boss epoll via
    // epoll_ctl(EPOLL_CTL_ADD). Fake fds (e.g. 100) fail with EBADF.
    // We obtain a real but unbound socket fd, pass it through
    // FakeNativeSocketOps.enqueueBindListener, and let the engine wire
    // it to epoll. No accept() is ever driven — we close the server
    // channel immediately, which unwinds the epoll registration and
    // closes the fd.

    @Test
    fun `bindInet happy path returns StreamServer with scripted local address`() = runBlocking {
        withTimeout(15.seconds) {
            val sentinelFd = socket(AF_INET, SOCK_STREAM, 0)
            check(sentinelFd >= 0) { "failed to create sentinel socket" }
            val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 18080)
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindListener(sentinelFd)
                enqueueLocalAddress(sentinelFd, scriptedLocal)
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val server = engine.bind(
                    InetSocketAddress(Host.Ip(IpAddress.parse("0.0.0.0")), 0),
                    BindConfig(),
                )
                assertEquals(scriptedLocal, server.localAddress)
                assertTrue(server.isActive)
                assertEquals(1, fakeOps.bindListenerCalls)
                assertEquals(1, fakeOps.getLocalAddressCalls)
                fakeOps.assertAllConsumed()
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
    fun `bindUnix happy path returns StreamServer with passed address`() = runBlocking {
        withTimeout(15.seconds) {
            val sentinelFd = socket(AF_INET, SOCK_STREAM, 0)
            check(sentinelFd >= 0) { "failed to create sentinel socket" }
            val addr = UnixSocketAddress("/tmp/keel-fake.sock")
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindUnixListener(sentinelFd)
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val server = engine.bind(addr, BindConfig())
                assertEquals(addr, server.localAddress)
                assertTrue(server.isActive)
                assertEquals(1, fakeOps.bindUnixListenerCalls)
                // bindUnix uses the caller-supplied address; no getLocalAddress lookup.
                assertEquals(0, fakeOps.getLocalAddressCalls)
                fakeOps.assertAllConsumed()
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
    fun `bindUnix surfaces bindUnixListener permission denied`() = runBlocking {
        withTimeout(15.seconds) {
            val expected = IllegalStateException("bind(AF_UNIX) failed: Permission denied")
            val fakeOps = FakeNativeSocketOps().apply {
                enqueueBindUnixListenerThrows(expected)
            }
            val engine = newEngine(fakeOps = fakeOps)
            try {
                val ex = assertFailsWith<IllegalStateException> {
                    engine.bind(UnixSocketAddress("/var/run/restricted.sock"), BindConfig())
                }
                assertTrue(ex.message!!.contains("Permission denied"), "got: ${ex.message}")
                assertEquals(1, fakeOps.bindUnixListenerCalls)
                fakeOps.assertAllConsumed()
            } finally {
                engine.close()
            }
        }
    }
}
