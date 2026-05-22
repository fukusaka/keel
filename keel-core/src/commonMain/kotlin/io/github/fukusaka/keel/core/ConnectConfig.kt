package io.github.fukusaka.keel.core

/**
 * Per-connect configuration for [StreamEngine.connect].
 *
 * Counterpart to [BindConfig] on the client side — lets the caller
 * tune TCP / UDS socket options before `connect(2)` is issued.
 *
 * ```
 * Config scope:
 *   IoEngineConfig  -- engine-wide (allocator, threads)
 *   BindConfig      -- per-server  (backlog, child socket options)
 *   ConnectConfig   -- per-client  (socket options)
 *   Channel props   -- per-channel (runtime)
 * ```
 *
 * Engines apply [socketOptions] immediately after creating the
 * client socket and before issuing `connect(2)`, so the options
 * take effect for the three-way handshake and all subsequent data
 * transfer.
 *
 * @param socketOptions Socket options applied to the client fd.
 *   Default: [SocketOptions.DEFAULT] (`TCP_NODELAY` enabled).
 * @param readBufferSize Per-client override of the read buffer size for
 *   this connection (see [IoEngineConfig.readBufferSize]). `null` (default)
 *   inherits the engine-wide [IoEngineConfig.readBufferSize]. Fixed for the
 *   connection's lifetime. If non-null, must be a power of two in
 *   [IoEngineConfig.MIN_READ_BUFFER_SIZE]..[IoEngineConfig.MAX_READ_BUFFER_SIZE].
 *   Honoured by the pull-model POSIX / NIO engines (epoll / kqueue / nio);
 *   io_uring uses a per-EventLoop shared buffer ring and falls back to the
 *   engine-wide value; push-model engines ignore it.
 */
public open class ConnectConfig(
    public val socketOptions: SocketOptions = SocketOptions.DEFAULT,
    public val readBufferSize: Int? = null,
) {

    init {
        readBufferSize?.let { IoEngineConfig.requireValidReadBufferSize(it) }
    }

    public companion object {
        /** Default config — [SocketOptions.DEFAULT] (`TCP_NODELAY` enabled). */
        public val DEFAULT: ConnectConfig = ConnectConfig()
    }
}
