package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Contract self-test for [FakeNativeSocketOps], the lifecycle-syscall seam the
 * POSIX engine seam tests script `bindListener` / `connect` / address results
 * through. Pins the two-mode model (scripted FIFO queue → counter/default
 * fallback), the throw path, the reusePort routing, and the call-tracking
 * lists, so a silent break does not weaken the engine tests that depend on it.
 *
 * Sibling of [FakeNativeSocketTest], which covers the hot-path
 * [FakeNativeSocket] fake; this one covers the [NativeSocketOps] lifecycle
 * fake, previously without a self-test.
 */
class FakeNativeSocketOpsTest {

    @Test
    fun `unscripted bindListener auto-increments from nextCreatedFd and records createdFds`() {
        val ops = FakeNativeSocketOps().apply { nextCreatedFd = 100 }
        val a = ops.bindListener(IpAddress.V4.ANY, 0, 128, reusePort = false)
        val b = ops.bindListener(IpAddress.V4.ANY, 0, 128, reusePort = false)
        assertEquals(100, a)
        assertEquals(101, b)
        assertEquals(listOf(100, 101), ops.createdFds)
        assertEquals(2, ops.bindListenerCalls)
    }

    @Test
    fun `scripted bindListener queue takes precedence then falls back to the counter`() {
        val ops = FakeNativeSocketOps().apply { nextCreatedFd = 100 }
        ops.enqueueBindListener(7, 8)
        assertEquals(7, ops.bindListener(IpAddress.V4.ANY, 0, 128, reusePort = false))
        assertEquals(8, ops.bindListener(IpAddress.V4.ANY, 0, 128, reusePort = false))
        // Queue drained — falls back to the auto-increment counter.
        assertEquals(100, ops.bindListener(IpAddress.V4.ANY, 0, 128, reusePort = false))
        assertEquals(listOf(7, 8, 100), ops.createdFds)
    }

    @Test
    fun `enqueued throw is raised from the bindListener body`() {
        val ops = FakeNativeSocketOps()
        val boom = IllegalStateException("EADDRINUSE")
        ops.enqueueBindListenerThrows(boom)
        val thrown = assertFailsWith<IllegalStateException> {
            ops.bindListener(IpAddress.V4.ANY, 0, 128, reusePort = false)
        }
        assertSame(boom, thrown)
    }

    @Test
    fun `reusePort binds route to a separate queue and call counter`() {
        val ops = FakeNativeSocketOps()
        ops.enqueueBindListenerReusePort(50)
        assertEquals(50, ops.bindListener(IpAddress.V4.ANY, 0, 128, reusePort = true))
        assertEquals(1, ops.bindListenerReusePortCalls)
        assertEquals(0, ops.bindListenerCalls)
    }

    @Test
    fun `connect returns scripted per-fd results then the default`() {
        val ops = FakeNativeSocketOps().apply { defaultConnect = ConnectResult.InProgress }
        ops.enqueueConnect(5, ConnectResult.Connected, ConnectResult.Failed(111))
        assertEquals(ConnectResult.Connected, ops.connectNonBlocking(5, IpAddress.V4.ANY, 0))
        assertEquals(ConnectResult.Failed(111), ops.connectNonBlocking(5, IpAddress.V4.ANY, 0))
        // Queue drained → default.
        assertEquals(ConnectResult.InProgress, ops.connectNonBlocking(5, IpAddress.V4.ANY, 0))
        // A different fd was never scripted → default from the first call.
        assertEquals(ConnectResult.InProgress, ops.connectNonBlocking(6, IpAddress.V4.ANY, 0))
        assertEquals(4, ops.connectCalls)
    }

    @Test
    fun `getSocketError returns scripted per-fd values then the default`() {
        val ops = FakeNativeSocketOps().apply { defaultSocketError = 0 }
        ops.enqueueSocketError(9, 111)
        assertEquals(111, ops.getSocketError(9))
        assertEquals(0, ops.getSocketError(9))
    }

    @Test
    fun `address fallbacks map first to remote and second to local`() {
        val remote = InetSocketAddress(Host.Ip(IpAddress.V4.ANY), 1)
        val local = InetSocketAddress(Host.Ip(IpAddress.V4.ANY), 2)
        val ops = FakeNativeSocketOps().apply { defaultAddresses = remote to local }
        assertEquals(remote, ops.getRemoteAddress(3))
        assertEquals(local, ops.getLocalAddress(3))
    }

    @Test
    fun `scripted addresses take precedence over the defaults`() {
        val scriptedLocal = InetSocketAddress(Host.Ip(IpAddress.V4.ANY), 7)
        val ops = FakeNativeSocketOps()
        ops.enqueueLocalAddress(4, scriptedLocal)
        assertEquals(scriptedLocal, ops.getLocalAddress(4))
    }

    @Test
    fun `setNonBlocking and setSocketOption record ordered invocations`() {
        val ops = FakeNativeSocketOps()
        ops.setNonBlocking(11)
        ops.setNonBlocking(12)
        ops.setSocketOption(11, SocketOption.TcpNoDelay(true))
        ops.setSocketOption(11, SocketOption.KeepAlive(true))
        assertEquals(listOf(11, 12), ops.nonBlockingFds)
        assertEquals(2, ops.setNonBlockingCalls)
        assertEquals(
            listOf<Pair<Int, SocketOption>>(
                11 to SocketOption.TcpNoDelay(true),
                11 to SocketOption.KeepAlive(true),
            ),
            ops.appliedOptions,
        )
        assertEquals(2, ops.setSocketOptionCalls)
    }

    @Test
    fun `tracking lists are defensive copies`() {
        val ops = FakeNativeSocketOps()
        ops.setNonBlocking(1)
        val snapshot = ops.nonBlockingFds
        ops.setNonBlocking(2)
        // The earlier snapshot must not observe the later mutation.
        assertEquals(listOf(1), snapshot)
        assertEquals(listOf(1, 2), ops.nonBlockingFds)
        assertTrue(ops.nonBlockingFds !== snapshot)
    }
}
