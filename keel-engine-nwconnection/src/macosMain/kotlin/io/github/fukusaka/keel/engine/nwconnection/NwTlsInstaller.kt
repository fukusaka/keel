package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsInstaller

/**
 * Sentinel [TlsInstaller] for NWConnection listener-level TLS.
 *
 * NWConnection handles TLS at the listener level via `nw_parameters_create_secure_tcp`,
 * so per-connection TLS setup is not needed. This sentinel is used for type detection
 * in [NwEngine.bindPipeline] — its [install] method should never be called.
 *
 * Usage:
 * ```
 * val config = TlsConnectorConfig(tlsConfig, NwTlsInstaller)
 * engine.bindPipeline(host, port, config) { channel -> ... }
 * ```
 */
object NwTlsInstaller : TlsInstaller {

    override fun install(channel: PipelinedChannel, config: TlsConfig) {
        error("NwTlsInstaller should not be called per-connection; TLS is handled at the listener level")
    }
}
