package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Custom TLS installer for engine-specific TLS implementations.
 *
 * Engines that provide native TLS support (e.g., Netty's `SslHandler`)
 * implement this interface to install TLS at the transport level instead
 * of using keel's [TlsHandler] in the pipeline.
 */
fun interface TlsInstaller {
    /** Installs TLS on [channel] using the given [config]. */
    fun install(channel: PipelinedChannel, config: TlsConfig)
}
