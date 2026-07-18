package io.github.fukusaka.keel.client.http

/**
 * Tuning for the client's [ConnectionPool].
 *
 * @property maxIdleConnectionsPerRoute the most idle (kept-alive, waiting)
 *   connections the pool holds per route (`host:port`). Beyond this a
 *   released connection is closed rather than pooled. Concurrency is not
 *   capped — a burst opens as many connections as it needs; only the idle
 *   pool is bounded (the OkHttp model).
 * @property idleTimeoutMillis how long an idle connection may sit in the
 *   pool before it is discarded on the next lease (servers close idle
 *   keep-alive connections, so reusing a long-idle one tends to fail).
 *   `0` disables the timeout.
 */
internal data class PoolConfig(
    val maxIdleConnectionsPerRoute: Int = DEFAULT_MAX_IDLE_CONNECTIONS_PER_ROUTE,
    val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
) {
    init {
        require(maxIdleConnectionsPerRoute >= 0) {
            "maxIdleConnectionsPerRoute must be >= 0, was $maxIdleConnectionsPerRoute"
        }
        require(idleTimeoutMillis >= 0) {
            "idleTimeoutMillis must be >= 0 (0 disables), was $idleTimeoutMillis"
        }
    }

    companion object {
        const val DEFAULT_MAX_IDLE_CONNECTIONS_PER_ROUTE = 5
        const val DEFAULT_IDLE_TIMEOUT_MILLIS = 30_000L
    }
}
