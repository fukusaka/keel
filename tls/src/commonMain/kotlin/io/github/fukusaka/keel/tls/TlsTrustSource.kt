package io.github.fukusaka.keel.tls

/**
 * Source of trusted CA certificates for peer verification.
 *
 * During the TLS handshake, the peer's certificate chain is validated against
 * these trust anchors. The appropriate [TlsTrustSource] depends on the deployment:
 * - [SystemDefault] for production (uses OS/JDK trust store)
 * - [Pem] for custom CA (e.g. internal PKI)
 * - [InsecureTrustAll] for testing only (disables all verification)
 */
sealed interface TlsTrustSource {

    /** Use the platform/OS default trust store (e.g. /etc/ssl/certs, JDK cacerts). */
    data object SystemDefault : TlsTrustSource

    /** PEM-encoded CA certificate(s) for custom trust anchors. */
    class Pem(val caPem: String) : TlsTrustSource

    /**
     * Trust all certificates without verification.
     *
     * **Testing only.** Equivalent to `curl -k`. Do not use in production.
     */
    data object InsecureTrustAll : TlsTrustSource
}
