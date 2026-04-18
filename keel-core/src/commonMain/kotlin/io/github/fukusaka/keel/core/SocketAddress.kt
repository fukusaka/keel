package io.github.fukusaka.keel.core

/**
 * A network socket address.
 *
 * [InetSocketAddress] covers TCP / UDP / QUIC IPv4 and IPv6 endpoints;
 * [UnixSocketAddress] covers AF_UNIX / Unix Domain Socket endpoints.
 * Additional transports (e.g. VSOCK) can be added as further subclasses
 * without breaking callers that already pattern-match on the sealed
 * hierarchy.
 *
 * @see InetSocketAddress
 * @see UnixSocketAddress
 */
sealed class SocketAddress

/**
 * An IP socket endpoint — IP literal or hostname, plus a port.
 *
 * [host] is a [Host] sealed type that distinguishes an already-resolved
 * numeric IP ([Host.Ip]) from a hostname that still needs DNS lookup
 * ([Host.Name]). The string-form constructor parses IP literals eagerly
 * into [Host.Ip], so `InetSocketAddress("127.0.0.1", 80)` is resolved
 * at construction while `InetSocketAddress("example.com", 80)` is not.
 *
 * Equality is field-wise (data class): `Host.Name("localhost")` and
 * `Host.Ip(IpAddress.V4.LOOPBACK)` compare unequal, matching Java's
 * `InetSocketAddress.isUnresolved()` semantics.
 *
 * @property host IP literal or hostname wrapped in [Host].
 * @property port TCP/UDP/QUIC port (0 = OS-assigned ephemeral).
 */
data class InetSocketAddress(
    val host: Host,
    val port: Int,
) : SocketAddress() {

    init { require(port in 0..65535) { "port out of range: $port" } }

    /**
     * Convenience factory accepting a string form.
     *
     * - IPv4 / IPv6 literal (optionally bracketed and/or with a `%scope`
     *   suffix) is parsed eagerly into [Host.Ip].
     * - Anything else is stored as [Host.Name] and resolved lazily when
     *   the engine uses this address.
     */
    constructor(hostOrIp: String, port: Int) : this(
        IpAddress.parseOrNull(hostOrIp)?.let(Host::Ip) ?: Host.Name(hostOrIp),
        port,
    )

    /**
     * `true` if the host is a raw hostname (not yet resolved to an IP).
     * Resolved IP literals return `false`.
     */
    val isUnresolved: Boolean get() = host is Host.Name

    /**
     * String form of the host — hostname as-is, or the canonical IP
     * literal (RFC 5952 for V6, dotted-decimal for V4). Convenient for
     * logging and for code that previously held a raw `host: String`.
     */
    val hostString: String get() = when (host) {
        is Host.Name -> host.value
        is Host.Ip -> host.address.toCanonicalString()
    }

    override fun toString(): String {
        val sb = StringBuilder()
        when (host) {
            is Host.Name -> sb.append(host.value)
            is Host.Ip -> when (host.address) {
                is IpAddress.V4 -> sb.append(host.address.toCanonicalString())
                is IpAddress.V6 -> sb.append('[').append(host.address.toCanonicalString()).append(']')
            }
        }
        sb.append(':').append(port)
        return sb.toString()
    }
}

/**
 * A Unix domain socket endpoint.
 *
 * @property path Absolute filesystem path (`/tmp/foo.sock`) or abstract-
 *   namespace name (starts with `\u0000` on Linux).
 */
data class UnixSocketAddress(val path: String) : SocketAddress() {
    override fun toString(): String = "unix:$path"
}

/**
 * The host component of an [InetSocketAddress].
 *
 * [Name] holds a hostname string that DNS resolution converts to one
 * or more [IpAddress]es at use time. [Ip] holds an already-resolved
 * numeric IP; engines use it directly without invoking the resolver.
 */
sealed class Host {
    /** Hostname awaiting DNS resolution. */
    data class Name(val value: String) : Host()

    /** Resolved IP literal. */
    data class Ip(val address: IpAddress) : Host()
}
