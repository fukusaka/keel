package io.github.keel.core

/**
 * A network socket address.
 *
 * Currently TCP-only (host + port). Will be redesigned to support
 * Unix Domain Sockets and other transports in a future phase.
 */
data class SocketAddress(
    val host: String,
    val port: Int,
)
