package io.github.keel.core

/**
 * A network socket address.
 *
 * Currently TCP-only (host + port). Will be redesigned to support
 * Unix Domain Sockets and other transports in a future phase.
 * See: sealed class with TcpAddress / UnixAddress subtypes.
 *
 * @property host IP address string (e.g. "127.0.0.1", "0.0.0.0").
 * @property port Port number. 0 indicates an OS-assigned ephemeral port.
 */
data class SocketAddress(
    val host: String,
    val port: Int,
)
