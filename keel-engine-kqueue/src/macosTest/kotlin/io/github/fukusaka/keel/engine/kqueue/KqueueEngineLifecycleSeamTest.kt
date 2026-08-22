package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketOption
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.LoggerFactory
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.native.posix.ConnectResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import io.github.fukusaka.keel.native.readiness.ReadinessSuspendRegister
import io.github.fukusaka.keel.testing.buf.PointerlessIoBuf
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
 * Seam-level unit tests for `KqueueEngine` lifecycle branches via
 * `FakeNativeSocketOps` injection. macOS counterpart of
 * `EpollEngineLifecycleSeamTest` — same 5-case coverage of
 * `connect` `Failed` / `Connected` for TCP and UDS.
 *
 * Part of the project's two-layer seam + integration testing strategy
 * (this file covers the seam side).
 *
 * ## What this file does NOT cover
 *
 * - **`ConnectResult.InProgress` branch** — requires a real fd to
 *   become writable via `kevent(EVFILT_WRITE)`. Exercised by
 *   `KqueueEngineTest` integration tests.
 * - **`bind` happy path** — covered here via a real socket fd as
 *   sentinel (see `bindInet / bindUnix happy path` tests). `bindListener`
 *   is scripted to return a `socket(AF_INET, SOCK_STREAM, 0)` fd so
 *   `kevent(EV_ADD, serverFd)` on the boss loop succeeds; the engine
 *   then reads the scripted local address and constructs `ReadinessStreamServer`.
 *   Full accept flow (client → kernel → EVFILT_READ → accept) is still
 *   integration-only.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEngineLifecycleSeamTest {

    private fun newEngine(
        fakeSocket: FakeNativeSocket = FakeNativeSocket(),
        fakeOps: FakeNativeSocketOps = FakeNativeSocketOps(),
        suspendRegisterOverride: ReadinessSuspendRegister? = null,
    ): KqueueEngine = KqueueEngine(
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
                assertTrue(ex.message!!.contains("Connection refused"), "got: ${ex.message}")
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
                    "got: ${ex.message}",
                )
                assertEquals(1, fakeOps.bindListenerCalls)
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
                assertEquals(listOf(702 to SocketOption.TcpNoDelay(true)), fakeOps.appliedOptions)
                assertEquals(1, fakeOps.setSocketOptionCalls)
            } finally {
                engine.close()
            }
        }
    }

    // --- bind: happy path (real socket fd as sentinel) ---
    //
    // The engine's bind() registers serverFd with the boss kqueue via
    // kevent(EV_ADD). Fake fds (e.g. 100) fail. We obtain a real but
    // unbound socket fd, pass it through FakeNativeSocketOps.enqueueBindListener,
    // and let the engine wire it to kqueue. No accept() is ever driven —
    // we close the server channel immediately, which unwinds the kqueue
    // registration and closes the fd.

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

    @Test
    fun `an allocator whose buffers cannot reach a syscall is refused at construction`() {
        // The engine passes buffer memory straight to syscalls through an
        // unchecked cast, because that cast runs on every read and every
        // gather. Without this refusal the mistake surfaces once per
        // connection, as a ClassCastException inside a readiness dispatch,
        // and what the log shows is every connection dying rather than the
        // one thing that is wrong.
        val refused = assertFailsWith<IllegalArgumentException> {
            KqueueEngine(
                config = IoEngineConfig(threads = 1, allocator = PointerlessAllocator()),
                nativeSocket = FakeNativeSocket(),
                nativeSocketOps = FakeNativeSocketOps(),
            )
        }

        val message = checkNotNull(refused.message)
        assertTrue(message.contains("KqueueEngine"), "the engine refusing must be named, got: $message")
        assertTrue(
            message.contains("PointerlessAllocator"),
            "and the allocator the user configured, got: $message",
        )
    }

    @Test
    fun `the check asks at the read size this engine was configured with`() = runBlocking {
        withTimeout(15.seconds) {
            // The engine passes its own configured size, not the interface
            // default. A pooled allocator can build a small buffer and a large
            // one through different seams, so a call site that handed over a
            // constant would attest a seam this engine never reads through.
            val sizes = mutableListOf<Int>()
            val configured = SizeRecordingAllocator(sizes)
            val engine = KqueueEngine(
                config = IoEngineConfig(
                    threads = 1,
                    readBufferSize = PROBE_READ_BUFFER_SIZE,
                    allocator = configured,
                ),
            )

            try {
                assertEquals(
                    listOf(PROBE_READ_BUFFER_SIZE),
                    sizes,
                    "the check must ask once, at the size this engine reads at",
                )
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `a refused allocator leaves nothing built`() {
        // The check runs before anything else is built. The constructor does
        // roll back what its own `try` covers, so most of a later placement
        // would still unwind — but not the boss loop, which is built between
        // the check and that `try` and holds a readiness descriptor, a wakeup
        // primitive and an arena. A refusal there leaves them with nothing
        // holding a reference to close them, because the reference never leaves
        // the constructor.
        //
        // Two observables, because one is not enough. The allocator sees the
        // children an engine asks for to keep. The logger factory sees the boss
        // loop being built, which holds a kqueue descriptor and a wakeup pipe
        // and comes first -- a check moved below it would still leave the
        // allocator's count at zero.
        val refusing = PointerlessAllocator()
        val loggers = RecordingLoggerFactory()

        assertFailsWith<IllegalArgumentException> {
            KqueueEngine(
                config = IoEngineConfig(threads = 1, allocator = refusing, loggerFactory = loggers),
            )
        }

        assertEquals(0, refusing.working, "a refusal must not have built anything to leak")
        assertEquals(
            0,
            loggers.tagsAskedFor.count { it == "KqueueEventLoop" },
            "and must refuse before the event loops it would log through, got: ${loggers.tagsAskedFor}",
        )
    }

    /** Records which loggers an engine asked for, and in what order. */
    private class RecordingLoggerFactory : LoggerFactory {
        val tagsAskedFor = mutableListOf<String>()

        override fun logger(tag: String): Logger {
            tagsAskedFor += tag
            return NoopLoggerFactory.logger(tag)
        }
    }

    /**
     * Records the sizes the check asks for, and hands the engine something else.
     *
     * The children are plain: the engine's loops run on their own threads, and
     * a recorder they shared would be a list written from those threads and read
     * from this one. Only the check allocates through this instance, and it does
     * so on the constructing thread before any loop is started.
     */
    private class SizeRecordingAllocator(private val sizes: MutableList<Int>) : BufferAllocator by DefaultAllocator {
        override fun allocate(capacity: Int): IoBuf {
            sizes += capacity
            return DefaultAllocator.allocate(capacity)
        }

        override fun createChild(): BufferAllocator = DefaultAllocator
    }

    /**
     * Everything it hands out fails the pointer cast, and it counts the children
     * an engine asks for to keep.
     *
     * The count is what says a refusal built nothing: an engine that got as far
     * as its worker group asked for one child per worker first.
     */
    private class PointerlessAllocator : BufferAllocator by DefaultAllocator {
        var working = 0
            private set

        override fun allocate(capacity: Int): IoBuf = PointerlessIoBuf(DefaultAllocator.allocate(capacity))

        override fun createChild(): BufferAllocator {
            working++
            return this
        }
    }

    private companion object {
        /** Not the interface default, so a call site handing over a constant shows. */
        const val PROBE_READ_BUFFER_SIZE = 32 * 1024
    }
}
