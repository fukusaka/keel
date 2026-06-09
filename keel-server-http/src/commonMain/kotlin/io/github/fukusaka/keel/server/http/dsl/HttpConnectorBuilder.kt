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

    /**
     * Header-complete deadline in milliseconds: the time budget from the first byte
     * of a request to its complete request head. `0` (default) disables it. When set,
     * a slow-header (classic slowloris) peer that trickles the request head is
     * force-closed once the budget elapses — the codec-layer completion-deadline that
     * the transport idle timeout cannot enforce (a byte trickle keeps refreshing an
     * inactivity timer, but not this absolute deadline). Analogous to nginx
     * `client_header_timeout` / Apache `RequestReadTimeout header=…`.
     */
    public var headerTimeoutMillis: Long = 0

    /**
     * Request-total deadline in milliseconds: an absolute ceiling on the time from
     * the first byte of a request to its complete body. `0` (default) disables it.
     * When set, a slow-body peer that trickles the request body past the budget is
     * force-closed. It is a generous hard ceiling — set it above the largest
     * legitimate upload's expected duration; fine-grained slow-vs-attack
     * discrimination (a minimum-throughput rate floor) is a separate control.
     * Analogous to (but stricter than) nginx `client_body_timeout`.
     */
    public var requestTimeoutMillis: Long = 0

    /**
     * Minimum sustained request-body throughput in bytes per second. `0` (default)
     * disables it. When set, a slow-body peer whose body stalls below this floor is
     * force-closed even while it stays under the absolute [requestTimeoutMillis] ceiling —
     * the fine-grained slow-vs-attack discrimination that a single generous deadline
     * cannot make. A legitimate slow upload that keeps steady progress passes. Analogous
     * to Apache `RequestReadTimeout body=…,MinRate=…`.
     */
    public var minBodyRateBytesPerSec: Long = 0

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

    /** The header-complete deadline ([headerTimeoutMillis]); `0` disables it. */
    internal fun buildHeaderTimeout(): Long = headerTimeoutMillis

    /** The request-total deadline ([requestTimeoutMillis]); `0` disables it. */
    internal fun buildRequestTimeout(): Long = requestTimeoutMillis

    /** The minimum body throughput floor ([minBodyRateBytesPerSec]); `0` disables it. */
    internal fun buildMinBodyRate(): Long = minBodyRateBytesPerSec
}
