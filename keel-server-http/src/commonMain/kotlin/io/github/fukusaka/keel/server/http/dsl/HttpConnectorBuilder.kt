package io.github.fukusaka.keel.server.http.dsl

import io.github.fukusaka.keel.codec.http.HttpHeaderLimitsConfig
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.server.ServerConnector
import io.github.fukusaka.keel.server.dsl.KeelServerDsl
import io.github.fukusaka.keel.server.dsl.ServerTlsBuilder
import io.github.fukusaka.keel.server.http.QueryParameterConfig
import io.github.fukusaka.keel.server.dsl.connector as buildServerConnector

/**
 * HTTP-aware connector builder for [keelHttpServer].
 *
 * Re-exposes the protocol-neutral connector fields of `:keel-server`
 * ([host] / [port] / [backlog] / [socketOptions] / [tls]) and adds the
 * HTTP-specific [queryParameters] block. `:keel-server`'s own
 * `ServerConnectorBuilder` stays protocol-neutral and cannot host an
 * HTTP concept such as query-string parsing — so the HTTP server's
 * `connector { }` block is this type instead.
 *
 * The connector fields are forwarded, at [buildConnector] time, into
 * `:keel-server`'s public `connector { }` function, which owns the
 * [ServerConnector] construction.
 */
@KeelServerDsl
public class HttpConnectorBuilder internal constructor() {

    /** Bind address. Defaults to [ServerConnector.DEFAULT_HOST]. */
    public var host: String = ServerConnector.DEFAULT_HOST

    /** Bind port. Defaults to [ServerConnector.DEFAULT_PORT] (OS-assigned). */
    public var port: Int = ServerConnector.DEFAULT_PORT

    /** TCP listen backlog. Defaults to [BindConfig.DEFAULT_BACKLOG]. */
    public var backlog: Int = BindConfig.DEFAULT_BACKLOG

    /** Socket options applied to every accepted client fd. */
    public var socketOptions: SocketOptions = SocketOptions.DEFAULT

    private var tlsConfigure: (ServerTlsBuilder.() -> Unit)? = null
    private var queryConfig: QueryParameterConfig = QueryParameterConfig.DEFAULT
    private var headerLimitsConfig: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig.DEFAULT

    /**
     * Enables TLS on this connector. The block must set both
     * `config` and `strategy` (see `:keel-server`'s `ServerTlsBuilder`).
     * The lambda is stored and forwarded to the underlying connector at
     * [buildConnector] time.
     */
    public fun tls(configure: ServerTlsBuilder.() -> Unit) {
        tlsConfigure = configure
    }

    /**
     * Configures query-string parsing limits — the `maxParameterCount`
     * DoS guard and the opt-in [QueryParameterConfigBuilder.rejectControlCharacters]
     * / [QueryParameterConfigBuilder.rejectMalformedEncoding] strict
     * modes (see [QueryParameterConfig]). When omitted,
     * [QueryParameterConfig.DEFAULT] is used.
     */
    public fun queryParameters(configure: QueryParameterConfigBuilder.() -> Unit) {
        queryConfig = QueryParameterConfigBuilder().apply(configure).build()
    }

    /**
     * Configures the per-request header limits enforced by the
     * codec — currently `maxHeaderCount` (see
     * [HttpHeaderLimitsConfig]). The block extends here, alongside
     * [queryParameters], to keep keel's DoS-guard surface a single
     * coordinate (`connector { headerLimits { … } }`) rather than
     * scattering it across the server's wider builder.
     */
    public fun headerLimits(configure: HttpHeaderLimitsConfigBuilder.() -> Unit) {
        headerLimitsConfig = HttpHeaderLimitsConfigBuilder().apply(configure).build()
    }

    /**
     * Builds the [ServerConnector] by forwarding this builder's connector
     * fields into `:keel-server`'s public `connector { }` function.
     */
    internal fun buildConnector(): ServerConnector =
        buildServerConnector {
            host = this@HttpConnectorBuilder.host
            port = this@HttpConnectorBuilder.port
            backlog = this@HttpConnectorBuilder.backlog
            socketOptions = this@HttpConnectorBuilder.socketOptions
            this@HttpConnectorBuilder.tlsConfigure?.let { tls(it) }
        }

    /** The query-parameter configuration set by [queryParameters], or the default. */
    internal fun buildQueryConfig(): QueryParameterConfig = queryConfig

    /** The header-limits configuration set by [headerLimits], or the default. */
    internal fun buildHeaderLimits(): HttpHeaderLimitsConfig = headerLimitsConfig
}
