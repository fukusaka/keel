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
 *
 * ## Adding a future version (e.g. TLS 1.3 → TLS 1.4)
 *
 * Adding an entry here makes every backend's mapping `when` non-exhaustive,
 * so the compiler forces each backend to consciously **map or reject** the
 * new version — there is no silent omission. During the transition where
 * only some backends' linked libraries support it:
 *
 * 1. Backends with library support map the new constant; backends without
 *    it `throw` a clear "not supported by this backend" [TlsException]
 *    (honor-or-reject — never a silent downgrade).
 * 2. **Leave the default ceiling unchanged** (the
 *    [TlsConfig.maxVersion]`= null` cap stays at the highest version *all*
 *    backends support) so default connections keep working everywhere;
 *    callers opt into the newer version explicitly via [TlsConfig.maxVersion]
 *    on the backends that support it.
 * 3. Once every backend supports it, raise the default ceiling in one step.
 */
public enum class TlsVersion {
    /** TLS 1.2 (RFC 5246). */
    TLS1_2,

    /** TLS 1.3 (RFC 8446). */
    TLS1_3,
}
