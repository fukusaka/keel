package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsInstaller

/**
 * Sentinel [TlsInstaller] for Node.js listener-level TLS.
 *
 * [NodeEngine][io.github.fukusaka.keel.engine.nodejs.NodeEngine] detects
 * this as a non-[TlsCodecFactory][io.github.fukusaka.keel.tls.TlsCodecFactory]
 * installer and creates a `tls.Server` via `tls.createServer()`. The
 * per-connection [install] is never called because `initializeConnection`
 * is skipped for listener-level TLS.
 */
internal object NodeTlsInstaller : TlsInstaller {
    override fun install(channel: PipelinedChannel, config: TlsConfig) {
        error("NodeTlsInstaller should not be called per-connection; TLS is handled at the listener level")
    }
}

/** Register the Node.js TLS installer provider for `--tls-installer=node`. */
internal fun registerNodeTlsInstaller() {
    registerTlsInstallerProvider { installer ->
        when (installer) {
            "node" -> NodeTlsInstaller
            else -> error("Unsupported TLS installer on JS: $installer (available: keel, node)")
        }
    }
}
