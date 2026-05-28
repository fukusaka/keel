package io.github.fukusaka.keel.tls

/**
 * TLS protocol version, used to pin the negotiated version range via
 * [TlsConfig.minVersion] / [TlsConfig.maxVersion].
 *
 * Only the two currently-recommended versions are exposed, by design:
 * SSLv3 / TLS 1.0 / TLS 1.1 are deprecated (RFC 8996) and keel forbids
 * them outright. They are absent from this enum (so they cannot be
 * requested) and [TlsConfig.minVersion] defaults to [TLS1_2] and is set
 * explicitly on every backend (so the backend / system default can never
 * lower the floor below TLS 1.2).
 *
 * The enum order is ascending (`TLS1_2 < TLS1_3`), so a range is valid
 * when `minVersion <= maxVersion`.
 *
 * Maps to each backend's native representation:
 * - JSSE: `"TLSv1.2"` / `"TLSv1.3"` (`SSLEngine.enabledProtocols`)
 * - OpenSSL / AWS-LC: `TLS1_2_VERSION` / `TLS1_3_VERSION`
 *   (`SSL_CTX_set_min_proto_version` / `set_max_proto_version`)
 * - Mbed TLS: `MBEDTLS_SSL_VERSION_TLS1_2` / `MBEDTLS_SSL_VERSION_TLS1_3`
 *   (`mbedtls_ssl_conf_min_tls_version` / `max`)
 */
public enum class TlsVersion {
    /** TLS 1.2 (RFC 5246). */
    TLS1_2,

    /** TLS 1.3 (RFC 8446). */
    TLS1_3,
}
