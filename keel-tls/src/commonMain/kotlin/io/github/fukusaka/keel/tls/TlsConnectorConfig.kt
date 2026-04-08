package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Per-connector TLS configuration.
 *
 * Extends [BindConfig] so it can be passed directly to
 * [StreamEngine.bindPipeline][io.github.fukusaka.keel.core.StreamEngine.bindPipeline]
 * or [StreamEngine.bind][io.github.fukusaka.keel.core.StreamEngine.bind]
 * as the config parameter.
 *
 * Holds a [TlsConfig] (certificates, trust anchors, ALPN, etc.) and an
 * optional [TlsInstaller] for per-connection TLS setup.
 *
 * - **[installer] = non-null**: Per-connection TLS. [initializeConnection] calls
 *   [installer]`.install()` to set up TLS handlers per-connection.
 *   [TlsCodecFactory] installs keel's [TlsHandler]; engine-specific installers
 *   (e.g., `NettySslInstaller`) install at the transport level.
 * - **[installer] = null**: Engine-native TLS. Listener-level engines (Node.js,
 *   NWConnection) configure TLS at listener creation time. Per-connection engines
 *   (kqueue, epoll, NIO) do not support this and will error.
 *
 * @param config TLS settings (certificates, trust, verify mode, ALPN, SNI).
 * @param installer TLS installer for per-connection setup, or null for engine-native TLS.
 * @param backlog TCP listen backlog (inherited from [BindConfig]).
 */
class TlsConnectorConfig(
    val config: TlsConfig,
    val installer: TlsInstaller? = null,
    backlog: Int = DEFAULT_BACKLOG,
) : BindConfig(backlog) {

    /**
     * Installs TLS on the channel via [installer].
     *
     * No-op when [installer] is null (engine-native TLS handles TLS at
     * the listener level, so per-connection initialization is not needed).
     */
    override fun initializeConnection(channel: PipelinedChannel) {
        installer?.install(channel, config)
    }
}
