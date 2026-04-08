package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Per-connector TLS configuration.
 *
 * Implements [BindConfig] so it can be passed directly to
 * [StreamEngine.bindPipeline][io.github.fukusaka.keel.core.StreamEngine.bindPipeline]
 * as the `config` parameter.
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
 * For per-connection engines (kqueue, epoll, NIO, Netty, io_uring),
 * [initializeConnection] calls [installer]`.install()` to set up TLS.
 * Listener-level engines (Node.js, NWConnection) may inspect this config
 * directly and handle TLS at the transport level, skipping
 * [initializeConnection].
 *
 * @param config TLS settings (certificates, trust, verify mode, ALPN, SNI).
 * @param installer TLS installer for per-connection setup.
 */
data class TlsConnectorConfig(
    val config: TlsConfig,
    val installer: TlsInstaller,
) : BindConfig {

    /** Installs TLS on the channel via [installer]. */
    override fun initializeConnection(channel: PipelinedChannel) {
        installer.install(channel, config)
    }
}
