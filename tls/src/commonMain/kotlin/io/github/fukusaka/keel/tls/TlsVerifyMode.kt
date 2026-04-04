package io.github.fukusaka.keel.tls

/**
 * Peer certificate verification mode.
 *
 * Maps to platform-specific settings:
 * - JSSE: `SSLParameters.setEndpointIdentificationAlgorithm` + `setNeedClientAuth`
 * - Mbed TLS: `MBEDTLS_SSL_VERIFY_NONE` / `MBEDTLS_SSL_VERIFY_OPTIONAL` / `MBEDTLS_SSL_VERIFY_REQUIRED`
 * - OpenSSL: `SSL_VERIFY_NONE` / `SSL_VERIFY_PEER` / `SSL_VERIFY_PEER | SSL_VERIFY_FAIL_IF_NO_PEER_CERT`
 */
enum class TlsVerifyMode {
    /** No peer certificate verification. Use for self-signed certs in testing. */
    NONE,

    /** Verify peer certificate if presented, but don't require it. */
    PEER,

    /** Require and verify peer certificate. Handshake fails if not provided. */
    REQUIRED,
}
