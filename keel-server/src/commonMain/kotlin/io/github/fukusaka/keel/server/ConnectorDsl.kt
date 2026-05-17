package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.tls.TlsConfig

/**
 * Builds a [ServerConnector] with the protocol-neutral connector DSL.
 *
 * ```
 * val c = connector {
 *     host = "0.0.0.0"
 *     port = 8443
 *     backlog = 256
 *     tls {
 *         config = tlsConfig
 *         strategy = ServerTlsStrategy.KeelCodec(JsseTlsCodecFactory())
 *     }
 * }
 * ```
 *
 * The block configures an endpoint independent of any wire protocol;
 * `keelHttpServer`, the ktor adapter, and future protocol servers all
 * consume the resulting [ServerConnector].
 */
public fun connector(configure: ServerConnectorBuilder.() -> Unit): ServerConnector =
    ServerConnectorBuilder().apply(configure).build()

/** Configuration builder for [connector]. */
public class ServerConnectorBuilder internal constructor() {

    /** Bind address. Defaults to [ServerConnector.DEFAULT_HOST]. */
    public var host: String = ServerConnector.DEFAULT_HOST

    /** Bind port. Defaults to [ServerConnector.DEFAULT_PORT] (OS-assigned). */
    public var port: Int = ServerConnector.DEFAULT_PORT

    /** TCP listen backlog. Defaults to [BindConfig.DEFAULT_BACKLOG]. */
    public var backlog: Int = BindConfig.DEFAULT_BACKLOG

    /** Socket options applied to every accepted client fd. */
    public var socketOptions: SocketOptions = SocketOptions.DEFAULT

    private var tls: ServerTls? = null

    /**
     * Enables TLS on this connector. The block must set
     * [ServerTlsBuilder.config]; [ServerTlsBuilder.strategy] defaults to
     * [ServerTlsStrategy.EngineNative].
     */
    public fun tls(configure: ServerTlsBuilder.() -> Unit) {
        tls = ServerTlsBuilder().apply(configure).build()
    }

    internal fun build(): ServerConnector = ServerConnector(host, port, backlog, socketOptions, tls)
}

/** Configuration builder for the [ServerConnectorBuilder.tls] block. */
public class ServerTlsBuilder internal constructor() {

    /** TLS settings (certificates, trust, verify mode, ALPN, SNI). Required. */
    public var config: TlsConfig? = null

    /** TLS mechanism. Defaults to [ServerTlsStrategy.EngineNative]. */
    public var strategy: ServerTlsStrategy = ServerTlsStrategy.EngineNative

    internal fun build(): ServerTls {
        val tlsConfig = checkNotNull(config) { "tls { } requires a TlsConfig — set `config = ...`" }
        return ServerTls(tlsConfig, strategy)
    }
}
