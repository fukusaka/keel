package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.SocketOptions

/**
 * Maps keel's [SocketOptions] to the `(no_delay, enable_keepalive)`
 * integer-sentinel pair consumed by the `_with_options` variants in
 * `nwconnection.def`.
 *
 * `-1` = leave NW framework default (matches the `null` semantics of
 * [SocketOptions.tcpNoDelay] / [SocketOptions.keepAlive]).
 *
 * ## Unsupported properties
 *
 * NW framework does not expose buffer-size tuning. When
 * [SocketOptions.receiveBufferSize] or [SocketOptions.sendBufferSize] is
 * set, this helper silently ignores them — the platform has no
 * corresponding API. Applications that need tight buffer control must
 * use [io.github.fukusaka.keel.engine.epoll.EpollEngine] /
 * [io.github.fukusaka.keel.engine.kqueue.KqueueEngine] /
 * [io.github.fukusaka.keel.engine.nio.NioEngine] /
 * [io.github.fukusaka.keel.engine.netty.NettyEngine] instead.
 */
internal fun SocketOptions.toNwNoDelayFlag(): Int = when (tcpNoDelay) {
    null -> -1
    true -> 1
    false -> 0
}

internal fun SocketOptions.toNwKeepAliveFlag(): Int = when (keepAlive) {
    null -> -1
    true -> 1
    false -> 0
}
