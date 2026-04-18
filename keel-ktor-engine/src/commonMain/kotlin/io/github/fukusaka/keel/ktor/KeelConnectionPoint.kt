package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.ktor.http.HttpMethod
import io.ktor.http.RequestConnectionPoint
import io.ktor.http.URLProtocol

/**
 * Ktor [RequestConnectionPoint] derived from keel [SocketAddress] and the HTTP request head.
 *
 * Local/remote address information comes from the keel [Channel][io.github.fukusaka.keel.core.Channel].
 * Server host/port fall back to the Host header value when socket address is unavailable.
 *
 * [scheme] ("http" or "https") determines the [defaultPort] used by [localPort]
 * and [serverPort] when the actual port is unknown.
 */
internal class KeelConnectionPoint(
    private val localAddr: SocketAddress?,
    private val remoteAddr: SocketAddress?,
    override val version: String,
    override val uri: String,
    private val hostHeaderValue: String?,
    override val method: HttpMethod,
    override val scheme: String = "http",
) : RequestConnectionPoint {

    private val defaultPort = URLProtocol.createOrDefault(scheme).defaultPort

    @Deprecated("Use localPort or serverPort instead")
    override val host: String
        get() = localAddr.hostString() ?: hostHeaderValue?.substringBefore(":") ?: "localhost"

    @Deprecated("Use localPort or serverPort instead")
    override val port: Int
        get() = localAddr.portOrNull() ?: hostHeaderValue?.substringAfter(":", "80")?.toInt() ?: 80

    override val localPort: Int get() = localAddr.portOrNull() ?: defaultPort

    override val serverPort: Int
        get() = hostHeaderValue
            ?.substringAfterLast(":", defaultPort.toString())?.toInt()
            ?: localPort

    override val localHost: String get() = localAddr.hostString() ?: "localhost"
    override val serverHost: String get() = hostHeaderValue?.substringBeforeLast(":") ?: localHost
    override val localAddress: String get() = localAddr.hostString() ?: "localhost"
    override val remoteHost: String get() = remoteAddr.hostString() ?: "unknown"
    override val remotePort: Int get() = remoteAddr.portOrNull() ?: 0
    override val remoteAddress: String get() = remoteAddr.hostString() ?: "unknown"
}

private fun SocketAddress?.hostString(): String? = when (this) {
    is InetSocketAddress -> when (val h = host) {
        is Host.Name -> h.value
        is Host.Ip -> h.address.toCanonicalString()
    }
    is UnixSocketAddress -> path
    null -> null
}

private fun SocketAddress?.portOrNull(): Int? = when (this) {
    is InetSocketAddress -> port
    else -> null
}
