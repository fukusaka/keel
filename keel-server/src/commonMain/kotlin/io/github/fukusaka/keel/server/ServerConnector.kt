package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine

/**
 * Protocol-neutral declaration of a single listening endpoint.
 *
 * A connector bundles the three transport-level concerns that are
 * independent of the wire protocol served on top:
 *
 * - **endpoint** — [host] / [port],
 * - **transport** — [backlog] / [socketOptions],
 * - **TLS** — optional [tls].
 *
 * The protocol (HTTP/1.1, WebSocket, future MQTT, …) is decided by the
 * pipeline initializer, not the connector. This is why `ServerConnector`
 * lives in `:keel-server`: `keelHttpServer`, the ktor adapter, and future
 * protocol servers all consume the same connector shape.
 *
 * Build one with the [connector] DSL, then turn it into engine inputs at
 * bind time: [address] for the bind endpoint and [resolveBindConfig] for
 * the [BindConfig] (which materialises the TLS strategy against the
 * concrete engine).
 *
 * @param host bind address (e.g. "0.0.0.0" for all interfaces).
 * @param port port number. 0 lets the OS assign an ephemeral port.
 * @param backlog TCP listen backlog.
 * @param socketOptions socket options applied to every accepted client fd.
 * @param tls TLS intent, or null for plain TCP.
 */
public data class ServerConnector(
    val host: String = DEFAULT_HOST,
    val port: Int = DEFAULT_PORT,
    val backlog: Int = BindConfig.DEFAULT_BACKLOG,
    val socketOptions: SocketOptions = SocketOptions.DEFAULT,
    val tls: ServerTls? = null,
) {

    /** The bind endpoint as a [SocketAddress]. */
    public val address: SocketAddress
        get() = InetSocketAddress(host, port)

    /**
     * Resolves this connector's [tls] intent into a [BindConfig] for
     * [engine].
     *
     * - no TLS → a plain [BindConfig] carrying [backlog] / [socketOptions];
     * - [ServerTlsStrategy.EngineNative] → delegates to the engine's
     *   [ServerTlsProvider.nativeTlsBindConfig]; an engine without native
     *   TLS is rejected with an actionable error;
     * - [ServerTlsStrategy.KeelCodec] → a [TlsServerConfig] installing
     *   keel's `TlsHandler` via [TlsCodecServerInstaller];
     * - [ServerTlsStrategy.Custom] → a [TlsServerConfig] with the
     *   caller-supplied installer.
     *
     * @throws IllegalStateException if [ServerTlsStrategy.EngineNative] is
     *   used with an engine that does not implement [ServerTlsProvider].
     */
    public fun resolveBindConfig(engine: StreamEngine): BindConfig {
        val serverTls = tls ?: return BindConfig(backlog, socketOptions)
        return when (val strategy = serverTls.strategy) {
            ServerTlsStrategy.EngineNative -> {
                val provider = engine as? ServerTlsProvider
                    ?: error(
                        "${engine::class.simpleName} has no native server TLS; " +
                            "use ServerTlsStrategy.KeelCodec instead",
                    )
                provider.nativeTlsBindConfig(serverTls.config, backlog, socketOptions)
            }
            is ServerTlsStrategy.KeelCodec ->
                TlsServerConfig(serverTls.config, TlsCodecServerInstaller(strategy.factory), backlog, socketOptions)
            is ServerTlsStrategy.Custom ->
                TlsServerConfig(serverTls.config, strategy.installer, backlog, socketOptions)
        }
    }

    public companion object {
        /** Default bind address — all interfaces. */
        public const val DEFAULT_HOST: String = "0.0.0.0"

        /** Default port — 0 lets the OS assign an ephemeral port. */
        public const val DEFAULT_PORT: Int = 0
    }
}
