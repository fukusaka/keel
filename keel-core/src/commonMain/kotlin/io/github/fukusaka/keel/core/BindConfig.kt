package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Per-server bind configuration for [StreamEngine.bind] and [StreamEngine.bindPipeline].
 *
 * Provides bind-time parameters (e.g., listen backlog, accepted-client
 * socket options) and an optional per-connection initialization hook
 * (e.g., TLS handler installation).
 *
 * ```
 * Config scope:
 *   IoEngineConfig  -- engine-wide (allocator, threads)
 *   BindConfig      -- per-server  (backlog, child socket options, TLS)
 *   ConnectConfig   -- per-client  (socket options)
 *   Channel props   -- per-channel (runtime)
 * ```
 *
 * Subclass [BindConfig] to add protocol-specific settings.
 * `TlsServerConfig` (in `:keel-server`) extends this class with TLS
 * certificates and installer configuration.
 *
 * @param backlog TCP listen backlog. OS may cap or adjust this value.
 * @param childSocketOptions Socket options applied to every accepted
 *   client fd immediately after `accept(2)` and before the pipeline
 *   initializer runs. Listener-side options (`SO_REUSEADDR` /
 *   `SO_REUSEPORT`) are kernel invariants and NOT configurable here —
 *   they are set unconditionally by
 *   [io.github.fukusaka.keel.native.posix.NativeSocketOps.bindListener].
 *   Default: [SocketOptions.DEFAULT] (`TCP_NODELAY` enabled).
 * @param readBufferSize Per-server override of the read buffer size for
 *   connections accepted by this server (see
 *   [IoEngineConfig.readBufferSize]). `null` (default) inherits the engine-wide
 *   [IoEngineConfig.readBufferSize]. Captured per accepted connection and
 *   fixed for its lifetime. If non-null, must be a power of two in
 *   [IoEngineConfig.MIN_READ_BUFFER_SIZE]..[IoEngineConfig.MAX_READ_BUFFER_SIZE].
 *   Honoured by the pull-model POSIX / NIO engines (epoll / kqueue / nio);
 *   io_uring uses a per-EventLoop shared buffer ring and falls back to the
 *   engine-wide value; push-model engines ignore it.
 * @param idleTimeoutMillis Per-server override of the idle (no-progress) timeout
 *   for accepted connections (see [IoEngineConfig.idleTimeoutMillis]). `null`
 *   (default) inherits the engine-wide value; `0` disables it for this server.
 *   If non-null, must be `>= 0`. Currently honoured by the epoll / kqueue engines.
 */
open class BindConfig(
    val backlog: Int = DEFAULT_BACKLOG,
    val childSocketOptions: SocketOptions = SocketOptions.DEFAULT,
    val readBufferSize: Int? = null,
    val idleTimeoutMillis: Long? = null,
) {

    init {
        readBufferSize?.let { IoEngineConfig.requireValidReadBufferSize(it) }
        idleTimeoutMillis?.let { IoEngineConfig.requireValidIdleTimeout(it) }
    }

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
