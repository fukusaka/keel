package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig

/**
 * Server connector configuration describing a listening endpoint.
 *
 * Each connector represents a single (host, port) binding with optional
 * TLS. Multiple connectors enable HTTP + HTTPS on different ports.
 *
 * Currently placed in `:ktor-engine` as a temporary location. Will be
 * moved to a shared module (`:server-core` or similar) when the
 * `:server` module is created.
 *
 * @param host Bind address (e.g. "0.0.0.0" for all interfaces).
 * @param port Port number. 0 lets the OS assign an ephemeral port.
 * @param tls TLS configuration. null = plain TCP (HTTP).
 */
data class ServerConnector(
    val host: String = "0.0.0.0",
    val port: Int = 0,
    val tls: TlsConnectorConfig? = null,
)

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

/**
 * Custom TLS installer for engine-specific TLS implementations.
 *
 * Engines that provide native TLS support (e.g., Netty's [SslHandler])
 * implement this interface to install TLS at the transport level instead
 * of using keel's [TlsHandler] in the pipeline.
 *
 * Set on [TlsConnectorConfig.installer] to override the default.
 */
fun interface TlsInstaller {
    /** Installs TLS on [channel] using the given [config]. */
    fun install(channel: PipelinedChannel, config: TlsConfig)
}
