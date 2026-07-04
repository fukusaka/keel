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
     * Server name for SNI (Server Name Indication) and, where the backend
     * supports it, the expected peer hostname for certificate verification.
     *
     * Used in client mode to indicate the hostname being connected to.
     * The server uses this to select the appropriate certificate when
     * hosting multiple domains. null disables SNI.
     *
     * On the keel `TlsCodec` backends this is also the reference name the peer
     * certificate is verified against when hostname verification is active
     * (see [verifyHostname]) — a verifying client ([verifyMode] other than
     * [TlsVerifyMode.NONE]) with hostname verification on must set it.
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

    /**
     * Whether a client verifies that the server certificate matches the
     * expected hostname ([serverName]), independently of chain verification
     * ([verifyMode]).
     *
     * This is a separate axis from [verifyMode]: [verifyMode] governs whether
     * the certificate *chain* is trusted (and, on a server, whether a client
     * certificate is required), while this governs whether the presented
     * certificate's CN / SAN must match [serverName]. Applies to client codecs
     * only — a server does not verify a peer hostname.
     *
     * - `null` (default): secure-by-default — hostname is verified when the
     *   codec is a client, [verifyMode] is not [TlsVerifyMode.NONE], and
     *   [serverName] is set.
     * - `true`: force hostname verification; [serverName] must be set.
     * - `false`: verify the chain (per [verifyMode]) but skip hostname
     *   matching. For self-signed / dynamic-hostname development setups or
     *   deployments that pin trust by other means. `curl`'s behaviour without
     *   `--insecure` corresponds to the default; this flag's `false` value is
     *   closer to verifying the chain while ignoring the name.
     *
     * To disable verification entirely (chain and hostname), use
     * [verifyMode] = [TlsVerifyMode.NONE] or
     * [trustAnchors] = [TlsTrustSource.InsecureTrustAll].
     *
     * **Backend support**: wired on the keel `TlsCodec` backends (JSSE,
     * OpenSSL, AWS-LC, Mbed TLS). Engines that use their platform's native TLS
     * stack (Netty, NWConnection, Node.js) do not consume this yet.
     */
    val verifyHostname: Boolean? = null,
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
        require(verifyHostname != true || serverName != null) {
            "verifyHostname = true requires serverName to be set (there is no hostname to verify against)"
        }
    }
}
