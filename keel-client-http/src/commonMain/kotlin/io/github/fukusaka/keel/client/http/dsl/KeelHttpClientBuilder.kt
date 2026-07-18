package io.github.fukusaka.keel.client.http.dsl

import io.github.fukusaka.keel.client.http.ConnectionPool
import io.github.fukusaka.keel.client.http.KeelHttpClient
import io.github.fukusaka.keel.client.http.PoolConfig
import io.github.fukusaka.keel.core.StreamEngine

/**
 * Builder for [keelHttpClient].
 *
 * The client is defined by the injected [StreamEngine] plus an optional
 * [pool] block for keep-alive tuning. Default headers, per-request
 * timeouts, and redirects arrive later; keeping the builder now means
 * those additions do not change the call shape.
 */
public class KeelHttpClientBuilder internal constructor() {

    private val poolBuilder = PoolConfigBuilder()

    /**
     * Configures the connection pool (keep-alive reuse):
     *
     * ```
     * keelHttpClient(engine) {
     *     pool {
     *         maxIdleConnectionsPerRoute = 8
     *         idleTimeoutMillis = 60_000
     *     }
     * }
     * ```
     */
    public fun pool(configure: PoolConfigBuilder.() -> Unit) {
        poolBuilder.apply(configure)
    }

    internal fun build(engine: StreamEngine): KeelHttpClient =
        KeelHttpClient(ConnectionPool(engine, poolBuilder.build()))
}

/**
 * Configures a [KeelHttpClient]'s connection pool. Obtained from
 * [KeelHttpClientBuilder.pool].
 */
public class PoolConfigBuilder internal constructor() {

    /**
     * Maximum idle (kept-alive, waiting) connections the pool holds per
     * route (`host:port`). Beyond this a released connection is closed
     * rather than pooled. Concurrency is not capped — a burst opens as many
     * connections as it needs; only the idle pool is bounded. Default 5.
     */
    public var maxIdleConnectionsPerRoute: Int = PoolConfig.DEFAULT_MAX_IDLE_CONNECTIONS_PER_ROUTE

    /**
     * How long an idle connection may sit in the pool before it is discarded
     * on the next lease, in milliseconds. `0` disables the timeout.
     * Default 30000.
     */
    public var idleTimeoutMillis: Long = PoolConfig.DEFAULT_IDLE_TIMEOUT_MILLIS

    internal fun build(): PoolConfig = PoolConfig(maxIdleConnectionsPerRoute, idleTimeoutMillis)
}

/**
 * Builds a [KeelHttpClient] on [engine].
 *
 * The [engine] is owned by the caller and is never closed by the returned
 * client (but [KeelHttpClient.close] closes the client's pooled connections).
 *
 * ```
 * val client = keelHttpClient(engine)
 * val res = client.get("http://127.0.0.1:8080/hello")
 * println(res.status)        // HttpStatus(200)
 * println(res.bodyText())
 * ```
 */
public fun keelHttpClient(
    engine: StreamEngine,
    configure: KeelHttpClientBuilder.() -> Unit = {},
): KeelHttpClient = KeelHttpClientBuilder().apply(configure).build(engine)
