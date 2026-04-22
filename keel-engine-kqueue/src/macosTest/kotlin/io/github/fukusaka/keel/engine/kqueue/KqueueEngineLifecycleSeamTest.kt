package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.native.posix.ConnectResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import io.github.fukusaka.keel.native.posix.InternalTestApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import platform.posix.ECONNREFUSED
import platform.posix.ECONNRESET
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for `KqueueEngine` lifecycle branches via
 * `FakeNativeSocketOps` injection. macOS counterpart of
 * `EpollEngineLifecycleSeamTest` — same 5-case coverage of
 * `connect` `Failed` / `Connected` for TCP and UDS.
 *
 * Per `.claude/rules/testing.md` § "二層テスト戦略".
 *
 * ## What this file does NOT cover
 *
 * - **`ConnectResult.InProgress` branch** — requires a real fd to
 *   become writable via `kevent(EVFILT_WRITE)`. Exercised by
 *   `KqueueEngineTest` integration tests.
 * - **`bind` happy path** — after `bindListener` the engine runs
 *   `kevent(EV_ADD, serverFd)` on the boss event loop to arm accept
 *   readiness. With a fake fd this fails. Covered by integration.
 */
@OptIn(ExperimentalForeignApi::class, InternalTestApi::class)
class KqueueEngineLifecycleSeamTest {

    private fun newEngine(
        fakeSocket: FakeNativeSocket = FakeNativeSocket(),
        fakeOps: FakeNativeSocketOps = FakeNativeSocketOps(),
    ): KqueueEngine = KqueueEngine(
        config = IoEngineConfig(threads = 1),
        nativeSocket = fakeSocket,
        nativeSocketOps = fakeOps,
    )

    // --- connect Inet: Failed branch ---

    @Test
    fun `connectInet Failed ECONNREFUSED throws with errno message`() = runBlocking {
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

    @Test
    fun `connectInet Failed ECONNRESET throws with errno message`() = runBlocking {
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

    // --- connect Inet: Connected branch ---

    @Test
    fun `connectInet Connected returns channel with scripted addresses`() = runBlocking {
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

    // --- connect Unix: Failed / Connected ---

    @Test
    fun `connectUnix Failed ECONNREFUSED throws with errno message`() = runBlocking {
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

    @Test
    fun `connectUnix Connected returns channel`() = runBlocking {
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

    // --- bind: failure branches (throw-from-create) ---

    @Test
    fun `bindInet surfaces bindListener EADDRINUSE`() = runBlocking {
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

    @Test
    fun `bindUnix surfaces bindUnixListener permission denied`() = runBlocking {
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
