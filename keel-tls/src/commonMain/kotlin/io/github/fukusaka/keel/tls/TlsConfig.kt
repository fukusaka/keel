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
     * Lowest TLS protocol version the handshake may negotiate. null lets
     * the backend pick its default floor (TLS 1.2 on every backend keel
     * targets). Pin to [TlsVersion.TLS1_3] to require TLS 1.3.
     *
     * Every backend honours this; a handshake that cannot satisfy the
     * range fails (the peer offering only a lower version is rejected).
     */
    val minVersion: TlsVersion? = null,

    /**
     * Highest TLS protocol version the handshake may negotiate. null lets
     * the backend pick its default ceiling (TLS 1.3). Combined with
     * [minVersion] this bounds the acceptable range; an empty range
     * (`minVersion > maxVersion`) is rejected at codec creation.
     */
    val maxVersion: TlsVersion? = null,
) {
    init {
        if (minVersion != null && maxVersion != null) {
            require(minVersion <= maxVersion) {
                "minVersion ($minVersion) must be <= maxVersion ($maxVersion)"
            }
        }
    }
}
