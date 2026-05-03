package io.github.fukusaka.keel.native.posix

import io.github.fukusaka.keel.core.SocketOption
import io.github.fukusaka.keel.core.SocketOptions

/**
 * Applies every non-null property of [options] to [fd] via
 * [NativeSocketOps.setSocketOption]. No-op when [SocketOptions.isEmpty]
 * is true — avoids unnecessary syscalls on the happy path.
 *
 * Called by engines right after opening a client socket
 * ([NativeSocketOps.openClientSocket]) and right after accepting a
 * client connection, so the options take effect for the subsequent
 * `connect(2)` handshake / data transfer.
 *
 * Order matters for future debuggability — options are applied in
 * the order declared in [SocketOptions] (tcpNoDelay → keepAlive →
 * receiveBufferSize → sendBufferSize). Tests relying on
 * [FakeNativeSocketOps.appliedOptions] can assert this sequence.
 *
 * Any `setsockopt(2)` failure is logged and swallowed by the
 * [NativeSocketOps] implementation — option application is best-effort
 * and does not fail the surrounding bind / connect / accept flow.
 */
public fun NativeSocketOps.applySocketOptions(fd: Int, options: SocketOptions) {
    if (options.isEmpty) return
    options.tcpNoDelay?.let { setSocketOption(fd, SocketOption.TcpNoDelay(it)) }
    options.keepAlive?.let { setSocketOption(fd, SocketOption.KeepAlive(it)) }
    options.receiveBufferSize?.let { setSocketOption(fd, SocketOption.ReceiveBufferSize(it)) }
    options.sendBufferSize?.let { setSocketOption(fd, SocketOption.SendBufferSize(it)) }
}
