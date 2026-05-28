package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOption
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.UnixSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Direct unit tests for the [NativeSocketOps.applySocketOptions]
 * extension — the cold-path glue that translates a [SocketOptions]
 * value object into a sequence of [NativeSocketOps.setSocketOption]
 * calls.
 *
 * The extension is pure dispatch logic (no syscalls of its own), so it
 * is exercised against a local recording double rather than a real
 * kernel. The behaviours pinned here are the ones engine call sites
 * rely on: the empty-options no-op shortcut, the fixed application
 * order (`tcpNoDelay → keepAlive → receiveBufferSize → sendBufferSize`),
 * the non-null subset filtering, and fd pass-through.
 *
 * A bespoke [RecordingSocketOps] is used instead of the shared
 * `FakeNativeSocketOps` (in `keel-testing-internal`) to avoid a
 * project dependency cycle: `keel-testing-internal` already depends on
 * `keel-native-posix`, so this module's own test source set cannot
 * depend back on it.
 */
class SocketOptionApplyTest {

    @Test
    fun `applySocketOptions on empty options performs no setsockopt calls`() {
        val ops = RecordingSocketOps()
        ops.applySocketOptions(FD, SocketOptions())
        assertTrue(
            ops.applied.isEmpty(),
            "empty SocketOptions must short-circuit without any setSocketOption call",
        )
    }

    @Test
    fun `applySocketOptions applies all four options in declared order`() {
        val ops = RecordingSocketOps()
        ops.applySocketOptions(
            FD,
            SocketOptions(
                tcpNoDelay = true,
                keepAlive = false,
                receiveBufferSize = 8192,
                sendBufferSize = 16384,
            ),
        )
        assertEquals(
            listOf(
                FD to SocketOption.TcpNoDelay(true),
                FD to SocketOption.KeepAlive(false),
                FD to SocketOption.ReceiveBufferSize(8192),
                FD to SocketOption.SendBufferSize(16384),
            ),
            ops.applied,
            "all options must be applied in tcpNoDelay → keepAlive → receiveBufferSize → sendBufferSize order",
        )
    }

    @Test
    fun `applySocketOptions applies only the non-null subset preserving order`() {
        val ops = RecordingSocketOps()
        // Skip keepAlive and receiveBufferSize: only the set properties
        // should produce setSocketOption calls, and they must keep their
        // relative declared order.
        ops.applySocketOptions(
            FD,
            SocketOptions(tcpNoDelay = true, sendBufferSize = 16384),
        )
        assertEquals(
            listOf(
                FD to SocketOption.TcpNoDelay(true),
                FD to SocketOption.SendBufferSize(16384),
            ),
            ops.applied,
            "only non-null options must be applied, in declared order",
        )
    }

    @Test
    fun `applySocketOptions passes the target fd through to every option`() {
        val ops = RecordingSocketOps()
        ops.applySocketOptions(
            OTHER_FD,
            SocketOptions(tcpNoDelay = true, keepAlive = true),
        )
        assertTrue(
            ops.applied.all { it.first == OTHER_FD },
            "every setSocketOption call must target the fd passed to applySocketOptions: ${ops.applied}",
        )
    }

    /**
     * Minimal [NativeSocketOps] double that records [setSocketOption]
     * invocations as ordered `(fd, option)` pairs. Every other method
     * fails loudly: [applySocketOptions] must never touch the lifecycle
     * syscalls, and a regression that routes through one of them should
     * surface as a test failure rather than a silent no-op.
     */
    private class RecordingSocketOps : NativeSocketOps {

        val applied = mutableListOf<Pair<Int, SocketOption>>()

        override fun setSocketOption(fd: Int, option: SocketOption) {
            applied.add(fd to option)
        }

        override fun bindListener(address: IpAddress, port: Int, backlog: Int, reusePort: Boolean): Int = unused()
        override fun openClientSocket(family: IpAddress): Int = unused()
        override fun connectNonBlocking(fd: Int, address: IpAddress, port: Int): ConnectResult = unused()
        override fun getSocketError(fd: Int): Int = unused()
        override fun getLocalAddress(fd: Int): SocketAddress = unused()
        override fun getRemoteAddress(fd: Int): SocketAddress = unused()
        override fun setNonBlocking(fd: Int): Unit = unused()
        override fun bindUnixListener(address: UnixSocketAddress, backlog: Int): Int = unused()
        override fun openUnixClientSocket(): Int = unused()
        override fun connectUnixNonBlocking(fd: Int, address: UnixSocketAddress): ConnectResult = unused()

        private fun unused(): Nothing =
            error("applySocketOptions must only call setSocketOption")
    }

    private companion object {
        private const val FD = 7
        private const val OTHER_FD = 42
    }
}
