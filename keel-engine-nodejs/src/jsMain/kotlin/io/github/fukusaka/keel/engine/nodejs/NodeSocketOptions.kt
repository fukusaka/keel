package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.core.SocketOptions

/**
 * Applies [options] to a Node.js [Socket] via `setNoDelay` / `setKeepAlive`.
 *
 * Only [SocketOptions.tcpNoDelay] and [SocketOptions.keepAlive] are
 * applied — Node.js `net.Socket` does not expose `SO_RCVBUF` /
 * `SO_SNDBUF`, so [SocketOptions.receiveBufferSize] /
 * [SocketOptions.sendBufferSize] are silently ignored (matches the
 * platform-coverage note in [SocketOptions]'s KDoc).
 *
 * Short-circuits on [SocketOptions.isEmpty] so default-configured
 * connect / accept paths do not invoke the underlying Node methods.
 */
internal fun applySocketOptions(socket: Socket, options: SocketOptions) {
    if (options.isEmpty) return
    options.tcpNoDelay?.let { socket.setNoDelay(it) }
    options.keepAlive?.let { socket.setKeepAlive(it) }
}
