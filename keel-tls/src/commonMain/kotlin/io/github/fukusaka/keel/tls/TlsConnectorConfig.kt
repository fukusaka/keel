package io.github.fukusaka.keel.tls

/**
 * Per-connector TLS configuration.
 *
 * Holds a [TlsConfig] (certificates, trust anchors, ALPN, etc.) and a
 * [TlsCodecFactory] for creating per-connection codec instances.
 *
 * The factory is per-connector rather than shared, allowing different
 * TLS backends (e.g., JSSE on one port, OpenSSL on another) on the
 * same server. To share a factory, pass the same instance to multiple
 * connectors.
 *
 * When [installer] is set, it overrides the default keel [TlsHandler]
 * installation. The [codecFactory] is ignored in this case — the
 * installer is responsible for all TLS setup.
 *
 * @param config TLS settings (certificates, trust, verify mode, ALPN, SNI).
 * @param codecFactory Factory for per-connection TlsCodec instances.
 * @param installer Custom TLS installer. null = use keel TlsHandler (default).
 */
data class TlsConnectorConfig(
    val config: TlsConfig,
    val codecFactory: TlsCodecFactory,
    val installer: TlsInstaller? = null,
)
