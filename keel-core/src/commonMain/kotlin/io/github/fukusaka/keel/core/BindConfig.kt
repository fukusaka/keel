package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Per-server bind configuration for [StreamEngine.bindPipeline].
 *
 * Implementations provide per-connection initialization logic (e.g., TLS
 * handler installation) that runs after accept and before the pipeline
 * initializer callback.
 *
 * ```
 * Config scope:
 *   IoEngineConfig  -- engine-wide (allocator, threads)
 *   BindConfig      -- per-server  (TLS, backlog: deferred)
 *   Channel props   -- per-channel (readTimeout, tcpNoDelay: deferred)
 * ```
 *
 * Currently a marker interface with a default per-connection hook.
 * Will evolve into a DSL builder as more per-server options are added
 * (backlog, SO_REUSEPORT, socket options).
 */
interface BindConfig {

    /**
     * Per-connection initializer called after accept, before the pipeline
     * initializer.
     *
     * Default: no-op. TLS implementations override this to install TLS
     * handlers per-connection. Listener-level engines (e.g., Node.js,
     * NWConnection) may skip this callback and handle TLS at the listener
     * level directly.
     */
    fun initializeConnection(channel: PipelinedChannel) {}
}
