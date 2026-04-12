package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Per-server bind configuration for [StreamEngine.bind] and [StreamEngine.bindPipeline].
 *
 * Provides bind-time parameters (e.g., listen backlog) and an optional
 * per-connection initialization hook (e.g., TLS handler installation).
 *
 * ```
 * Config scope:
 *   IoEngineConfig  -- engine-wide (allocator, threads)
 *   BindConfig      -- per-server  (backlog, TLS)
 *   Channel props   -- per-channel (readTimeout, tcpNoDelay: deferred)
 * ```
 *
 * Subclass [BindConfig] to add protocol-specific settings.
 * [TlsConnectorConfig][io.github.fukusaka.keel.tls.TlsConnectorConfig]
 * extends this class with TLS certificates and installer configuration.
 *
 * @param backlog TCP listen backlog. OS may cap or adjust this value.
 */
open class BindConfig(
    val backlog: Int = DEFAULT_BACKLOG,
) {

    /**
     * Per-connection initializer called after accept, before the pipeline
     * initializer.
     *
     * Default: no-op. TLS implementations override this to install TLS
     * handlers per-connection. Listener-level engines (e.g., Node.js,
     * NWConnection) may skip this callback and handle TLS at the listener
     * level directly.
     */
    open fun initializeConnection(channel: PipelinedChannel) {}

    companion object {
        /** Default TCP listen backlog (128). Common OS default on Linux and macOS. */
        const val DEFAULT_BACKLOG = 128
    }
}
