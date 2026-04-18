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
 * The [path] string accepts three forms:
 *
 * - **Filesystem path** (`"/tmp/foo.sock"`) — a real filesystem entry.
 *   Portable across Linux / macOS / BSD.
 * - **Abstract namespace (`@prefix`)** — `"@myapp.sock"` is equivalent to a
 *   Linux abstract socket with a leading null byte. This is the display
 *   convention used by `ss` / `netstat` / `systemd` (`ListenStream=@name`)
 *   and is accepted here as input for convenience, especially for config
 *   files where embedding a literal `\u0000` is awkward.
 * - **Abstract namespace (`\u0000` prefix)** — the kernel-level form
 *   (`"\u0000myapp.sock"`) with the null byte literally encoded.
 *   Accepted to match Go / Netty / Python conventions so code ported
 *   from those libraries works without rewriting.
 *
 * Abstract sockets are **Linux-only**. Engines reject abstract addresses
 * on macOS / BSD with an early [UnsupportedOperationException] rather
 * than surfacing a kernel-level errno. Filesystem sockets work on all
 * POSIX platforms.
 *
 * Equality is field-wise on [path], so `UnixSocketAddress("@a")` and
 * `UnixSocketAddress("\u0000a")` are **not** considered equal even
 * though they refer to the same kernel endpoint. Prefer the factory
 * methods [filesystem] / [abstract] for explicitness.
 *
 * @property path The address as written by the caller. Use [kernelPath]
 *   to obtain the form that `bind(2)` / `connect(2)` expect (with
 *   `@prefix` translated to a leading null byte).
 */
data class UnixSocketAddress(val path: String) : SocketAddress() {

    /** `true` if this address targets the Linux abstract namespace. */
    val isAbstract: Boolean get() = path.startsWith('\u0000') || path.startsWith('@')

    /**
     * Kernel-level form of [path]. Abstract names are normalised so the
     * first byte is `\u0000` (what the kernel reads from `sun_path[0]`);
     * filesystem paths are returned unchanged.
     */
    val kernelPath: String get() = when {
        path.startsWith('@') -> "\u0000" + path.substring(1)
        else -> path
    }

    override fun toString(): String = when {
        path.startsWith('\u0000') -> "unix:@" + path.substring(1)
        else -> "unix:$path"
    }

    companion object {
        /** Factory for a filesystem-backed Unix socket. */
        fun filesystem(path: String): UnixSocketAddress = UnixSocketAddress(path)

        /**
         * Factory for a Linux abstract-namespace Unix socket. The
         * stored [path] keeps the human-readable `@prefix` form so
         * [toString] round-trips cleanly; [kernelPath] renders the
         * `\u0000` form the kernel expects.
         */
        fun abstract(name: String): UnixSocketAddress = UnixSocketAddress("@$name")
    }
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
