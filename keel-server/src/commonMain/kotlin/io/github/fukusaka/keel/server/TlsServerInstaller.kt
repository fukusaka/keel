package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.tls.TlsConfig

/**
 * Installs server-side TLS on a [PipelinedChannel] using the given [TlsConfig].
 *
 * Engines that provide native TLS support (e.g., Netty's `SslHandler`)
 * implement this interface to install TLS at the transport level instead
 * of using keel's `TlsHandler` in the pipeline. Engines without native
 * TLS install keel's `TlsHandler` via [TlsCodecServerInstaller], an
 * adapter over [io.github.fukusaka.keel.tls.TlsCodecFactory].
 *
 * Set on [TlsServerConfig.installer] to choose the installer; `null`
 * means engine-native listener-level TLS (Node.js, NWConnection).
 *
 * Lives in `:keel-server` (not `:keel-tls`) because installing TLS on a
 * pipeline channel is server-binding plumbing, not TLS protocol code —
 * `:keel-tls` stays focused on protocol primitives (`TlsConfig`,
 * `TlsCodec`, `TlsCodecFactory`, `TlsHandler`).
 */
public fun interface TlsServerInstaller {
    /** Installs TLS on [channel] using the given [config]. */
    public fun install(channel: PipelinedChannel, config: TlsConfig)
}
