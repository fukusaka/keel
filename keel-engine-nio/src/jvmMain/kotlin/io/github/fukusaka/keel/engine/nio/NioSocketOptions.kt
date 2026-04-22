package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.SocketOptions
import java.net.StandardSocketOptions
import java.nio.channels.SocketChannel

/**
 * Applies [options] to [channel] via [SocketChannel.setOption], mirroring
 * the Native engines' `applySocketOptions` helper (see
 * `io.github.fukusaka.keel.native.posix.applySocketOptions`).
 *
 * Null properties are skipped so the kernel default remains; non-null
 * properties are mapped to [StandardSocketOptions] constants:
 *
 * - [SocketOptions.tcpNoDelay] → `TCP_NODELAY`
 * - [SocketOptions.keepAlive] → `SO_KEEPALIVE`
 * - [SocketOptions.receiveBufferSize] → `SO_RCVBUF`
 * - [SocketOptions.sendBufferSize] → `SO_SNDBUF`
 *
 * Short-circuits when [SocketOptions.isEmpty] to avoid per-connection
 * overhead when no options are configured.
 */
internal fun applySocketOptions(channel: SocketChannel, options: SocketOptions) {
    if (options.isEmpty) return
    options.tcpNoDelay?.let { channel.setOption(StandardSocketOptions.TCP_NODELAY, it) }
    options.keepAlive?.let { channel.setOption(StandardSocketOptions.SO_KEEPALIVE, it) }
    options.receiveBufferSize?.let { channel.setOption(StandardSocketOptions.SO_RCVBUF, it) }
    options.sendBufferSize?.let { channel.setOption(StandardSocketOptions.SO_SNDBUF, it) }
}
