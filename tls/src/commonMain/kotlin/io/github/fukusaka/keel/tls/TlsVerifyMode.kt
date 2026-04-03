package io.github.fukusaka.keel.tls

/**
 * Peer certificate verification mode.
 */
enum class TlsVerifyMode {
    /** No peer certificate verification. Use for self-signed certs in testing. */
    NONE,

    /** Verify peer certificate if presented, but don't require it. */
    PEER,

    /** Require and verify peer certificate. Handshake fails if not provided. */
    REQUIRED,
}
