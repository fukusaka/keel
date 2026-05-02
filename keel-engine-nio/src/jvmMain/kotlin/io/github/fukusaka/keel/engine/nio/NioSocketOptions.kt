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
 *
 * Each option is guarded by [SocketChannel.supportedOptions] so that
 * protocol-specific options (e.g. `TCP_NODELAY`) are silently skipped
 * on Unix Domain Socket channels, matching the POSIX behaviour where
 * `setsockopt(IPPROTO_TCP, TCP_NODELAY)` on an AF_UNIX fd returns
 * `ENOPROTOOPT` and is ignored.
 */
internal fun applySocketOptions(channel: SocketChannel, options: SocketOptions) {
    if (options.isEmpty) return
    val supported = channel.supportedOptions()
    options.tcpNoDelay?.let {
        if (StandardSocketOptions.TCP_NODELAY in supported) channel.setOption(StandardSocketOptions.TCP_NODELAY, it)
    }
    options.keepAlive?.let {
        if (StandardSocketOptions.SO_KEEPALIVE in supported) channel.setOption(StandardSocketOptions.SO_KEEPALIVE, it)
    }
    options.receiveBufferSize?.let {
        if (StandardSocketOptions.SO_RCVBUF in supported) channel.setOption(StandardSocketOptions.SO_RCVBUF, it)
    }
    options.sendBufferSize?.let {
        if (StandardSocketOptions.SO_SNDBUF in supported) channel.setOption(StandardSocketOptions.SO_SNDBUF, it)
    }
}
