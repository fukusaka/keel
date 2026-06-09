package io.github.fukusaka.keel.tls

/**
 * TLS connection configuration — reusable across multiple connections.
 *
 * Platform implementations convert this into their native config objects:
 * - Mbed TLS: `mbedtls_ssl_config`
 * - OpenSSL / AWS-LC: `SSL_CTX`
 * - JSSE: `SSLContext`
 * - NWConnection: `nw_parameters_t` with `sec_protocol_options`
 * - Node.js: `tls.createServer` / `tls.connect` options object
 *
 * A single [TlsConfig] can be shared by multiple [TlsCodec] instances.
 *
 * **Current scope**: server certificate + trust anchors + verification mode +
 * ALPN + SNI + protocol version range ([minVersion] / [maxVersion]). mTLS
 * (client auth), cipher-suite selection, session resumption, and 0-RTT are
 * deferred.
 */
data class TlsConfig(
    /**
     * Server certificate + private key.
     *
     * Required for server mode. For client mode, set only if the server
     * requires client certificate authentication (mTLS — future phase).
     */
    val certificates: TlsCertificateSource? = null,

    /**
     * Trusted CA certificates for peer verification.
     *
     * null uses [TlsTrustSource.SystemDefault] (OS/JDK trust store).
     * Set to [TlsTrustSource.InsecureTrustAll] for self-signed certs in testing.
     */
    val trustAnchors: TlsTrustSource? = null,

    /** Peer certificate verification mode. Defaults to [TlsVerifyMode.PEER]. */
    val verifyMode: TlsVerifyMode = TlsVerifyMode.PEER,

    /**
     * ALPN protocol list in preference order (e.g. `["h2", "http/1.1"]`).
     *
     * null disables ALPN negotiation. The negotiated protocol is available
     * via [TlsCodec.negotiatedProtocol] after handshake.
     */
    val alpnProtocols: List<String>? = null,

    /**
     * Server name for SNI (Server Name Indication).
     *
     * Used in client mode to indicate the hostname being connected to.
     * The server uses this to select the appropriate certificate when
     * hosting multiple domains. null disables SNI.
     */
    val serverName: String? = null,

    /**
     * Lowest TLS protocol version the handshake may negotiate. Defaults
     * to [TlsVersion.TLS1_2] and **is always enforced** — keel sets this
     * floor explicitly on every backend rather than deferring to the
     * backend / system default, so SSLv3 / TLS 1.0 / TLS 1.1 can never be
     * negotiated (those versions are also absent from [TlsVersion], so
     * there is no way to opt back into them). Pin to [TlsVersion.TLS1_3]
     * to require TLS 1.3.
     */
    val minVersion: TlsVersion = TlsVersion.TLS1_2,

    /**
     * Highest TLS protocol version the handshake may negotiate. null caps
     * at the newest version keel enumerates ([TlsVersion.TLS1_3] today).
     * Like [minVersion] the ceiling is set **explicitly** on every backend
     * — keel never negotiates a version it does not enumerate and test, so
     * a future TLS version is enabled only by adding it to [TlsVersion]
     * (not by a backend / system library silently gaining support).
     * Combined with [minVersion] this bounds the acceptable range; an
     * empty range (`minVersion > maxVersion`) is rejected at construction.
     */
    val maxVersion: TlsVersion? = null,

    /**
     * Absolute time budget (ms) for the TLS handshake to complete, measured
     * from the first inbound TLS record to [TlsCodec.isHandshakeComplete]. `0`
     * (default) disables it.
     *
     * When set, a peer that starts (or stalls) a handshake but never completes
     * it is force-closed once the budget elapses — the time-axis defence against
     * handshake resource holding that the transport idle timeout cannot enforce
     * (a slow handshake that trickles record bytes keeps refreshing an
     * inactivity timer, but not this absolute deadline). A peer that connects
     * and sends nothing is bounded by the transport idle timeout instead.
     * Enforced by [TlsHandler] via the per-EventLoop scheduler. Applies to
     * either role; on a server it bounds slow / stalled inbound handshakes.
     * Analogous to nginx `ssl_handshake_timeout`.
     */
    val handshakeTimeoutMillis: Long = 0,
) {
    init {
        if (maxVersion != null) {
            require(minVersion <= maxVersion) {
                "minVersion ($minVersion) must be <= maxVersion ($maxVersion)"
            }
        }
        require(handshakeTimeoutMillis >= 0) {
            "handshakeTimeoutMillis ($handshakeTimeoutMillis) must be >= 0 (0 disables the deadline)"
        }
    }
}
