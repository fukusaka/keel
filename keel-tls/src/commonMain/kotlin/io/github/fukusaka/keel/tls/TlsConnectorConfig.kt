package io.github.fukusaka.keel.tls

/**
 * Per-connector TLS configuration.
 *
 * Holds a [TlsConfig] (certificates, trust anchors, ALPN, etc.) and a
 * [TlsInstaller] for per-connection TLS setup.
 *
 * The installer is per-connector, allowing different TLS backends
 * (e.g., JSSE on one port, OpenSSL on another) on the same server.
 * To share an installer, pass the same instance to multiple connectors.
 *
 * [TlsCodecFactory] implements [TlsInstaller] and can be passed directly
 * as the installer. Engine-specific installers (e.g., `NettySslInstaller`)
 * install TLS at the transport level instead.
 *
 * @param config TLS settings (certificates, trust, verify mode, ALPN, SNI).
 * @param installer TLS installer for per-connection setup.
 */
data class TlsConnectorConfig(
    val config: TlsConfig,
    val installer: TlsInstaller,
)
