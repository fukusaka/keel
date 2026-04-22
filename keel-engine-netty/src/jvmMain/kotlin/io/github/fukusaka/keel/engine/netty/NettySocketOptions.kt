package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.SocketOptions
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelOption

/**
 * Applies [options] to [bootstrap] as client-channel options before
 * `bootstrap.connect()`. Null properties are skipped.
 *
 * Maps keel's typed [SocketOptions] to Netty's [ChannelOption]:
 *
 * - [SocketOptions.tcpNoDelay] → [ChannelOption.TCP_NODELAY]
 * - [SocketOptions.keepAlive] → [ChannelOption.SO_KEEPALIVE]
 * - [SocketOptions.receiveBufferSize] → [ChannelOption.SO_RCVBUF]
 * - [SocketOptions.sendBufferSize] → [ChannelOption.SO_SNDBUF]
 *
 * Netty applies the options to the channel in `initChannel` after it is
 * created but before `connect()` issues the syscall — equivalent timing
 * to NIO's `setOption` pre-connect and Native engines' `setsockopt(2)`.
 */
internal fun Bootstrap.applySocketOptions(options: SocketOptions): Bootstrap {
    if (options.isEmpty) return this
    options.tcpNoDelay?.let { option(ChannelOption.TCP_NODELAY, it) }
    options.keepAlive?.let { option(ChannelOption.SO_KEEPALIVE, it) }
    options.receiveBufferSize?.let { option(ChannelOption.SO_RCVBUF, it) }
    options.sendBufferSize?.let { option(ChannelOption.SO_SNDBUF, it) }
    return this
}

/**
 * Applies [options] to [bootstrap] as child (accepted) channel options
 * via `childOption`. Null properties are skipped.
 *
 * `childOption` is Netty's per-accepted-channel variant — the listener
 * socket itself is unaffected, mirroring the `BindConfig.childSocketOptions`
 * semantics used by Native engines (apply after `accept(2)`).
 */
internal fun ServerBootstrap.applyChildSocketOptions(options: SocketOptions): ServerBootstrap {
    if (options.isEmpty) return this
    options.tcpNoDelay?.let { childOption(ChannelOption.TCP_NODELAY, it) }
    options.keepAlive?.let { childOption(ChannelOption.SO_KEEPALIVE, it) }
    options.receiveBufferSize?.let { childOption(ChannelOption.SO_RCVBUF, it) }
    options.sendBufferSize?.let { childOption(ChannelOption.SO_SNDBUF, it) }
    return this
}
