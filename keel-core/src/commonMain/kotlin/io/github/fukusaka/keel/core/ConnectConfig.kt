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
 */
public open class ConnectConfig(
    public val socketOptions: SocketOptions = SocketOptions.DEFAULT,
) {
    public companion object {
        /** Default config — [SocketOptions.DEFAULT] (`TCP_NODELAY` enabled). */
        public val DEFAULT: ConnectConfig = ConnectConfig()
    }
}
