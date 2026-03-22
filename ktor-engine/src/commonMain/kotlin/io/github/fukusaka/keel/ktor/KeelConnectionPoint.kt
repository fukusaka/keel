package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.core.SocketAddress
import io.ktor.http.*

/**
 * Ktor [RequestConnectionPoint] derived from keel [SocketAddress] and the HTTP request head.
 *
 * Local/remote address information comes from the keel [Channel][io.github.fukusaka.keel.core.Channel].
 * Server host/port fall back to the Host header value when socket address is unavailable.
 */
internal class KeelConnectionPoint(
    private val localAddr: SocketAddress?,
    private val remoteAddr: SocketAddress?,
    override val version: String,
    override val uri: String,
    private val hostHeaderValue: String?,
    override val method: HttpMethod,
) : RequestConnectionPoint {
    override val scheme: String get() = "http"

    private val defaultPort = URLProtocol.createOrDefault(scheme).defaultPort

    @Deprecated("Use localPort or serverPort instead")
    override val host: String
        get() = localAddr?.host ?: hostHeaderValue?.substringBefore(":") ?: "localhost"

    @Deprecated("Use localPort or serverPort instead")
    override val port: Int
        get() = localAddr?.port ?: hostHeaderValue?.substringAfter(":", "80")?.toInt() ?: 80

    override val localPort: Int get() = localAddr?.port ?: defaultPort

    override val serverPort: Int
        get() = hostHeaderValue
            ?.substringAfterLast(":", defaultPort.toString())?.toInt()
            ?: localPort

    override val localHost: String get() = localAddr?.host ?: "localhost"
    override val serverHost: String get() = hostHeaderValue?.substringBeforeLast(":") ?: localHost
    override val localAddress: String get() = localAddr?.host ?: "localhost"
    override val remoteHost: String get() = remoteAddr?.host ?: "unknown"
    override val remotePort: Int get() = remoteAddr?.port ?: 0
    override val remoteAddress: String get() = remoteAddr?.host ?: "unknown"
}
