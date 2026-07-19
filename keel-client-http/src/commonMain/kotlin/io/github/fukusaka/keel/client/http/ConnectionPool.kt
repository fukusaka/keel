package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.core.StreamEngine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * A route-keyed pool of keep-alive [ClientConnection]s.
 *
 * A request [lease]s a connection — reusing an idle one for its route
 * (`host:port`) when available and still usable, otherwise opening a fresh
 * one — and [release]s it back when the exchange completes. A leased
 * connection is removed from the idle set, so it is owned by exactly one
 * caller at a time, which upholds the HTTP/1.1 serial-use invariant.
 *
 * Only the *idle* set is bounded ([PoolConfig.maxIdleConnectionsPerRoute]);
 * concurrency is not capped, so a burst opens as many connections as it
 * needs and the surplus is closed on release rather than pooled.
 *
 * **Thread safety**: all idle-set bookkeeping runs under [mutex]. Suspending
 * connection closes are always performed *outside* the lock (collected while
 * holding it, closed after releasing it) so a slow close never blocks other
 * leases/releases.
 *
 * The [engine] is owned by the caller and is never closed by the pool.
 */
internal class ConnectionPool(
    private val engine: StreamEngine,
    private val config: PoolConfig,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    private class Idle(val connection: ClientConnection, val idleSince: TimeMark)

    private val mutex = Mutex()
    private val idle = HashMap<RouteKey, ArrayDeque<Idle>>()
    private var closed = false

    /**
     * Leases a connection for [route]: a live, unexpired idle one (marked
     * [Lease.reused]) or a freshly opened one. Any idle connections found
     * stale (peer-closed or timed out) are closed.
     *
     * @throws IllegalStateException if the pool has been [close]d.
     */
    suspend fun lease(route: RouteKey): Lease {
        val stale = ArrayList<ClientConnection>()
        val pooled = mutex.withLock {
            check(!closed) { "client is closed" }
            val deque = idle[route]
            var chosen: ClientConnection? = null
            while (deque != null && deque.isNotEmpty()) {
                val entry = deque.removeLast() // LIFO: reuse the warmest connection first
                if (isUsable(entry)) {
                    chosen = entry.connection
                    break
                }
                stale.add(entry.connection)
            }
            if (deque != null && deque.isEmpty()) idle.remove(route)
            chosen
        }
        stale.forEach { it.close() }
        return if (pooled != null) Lease(pooled, reused = true) else Lease(openFresh(route), reused = false)
    }

    /** Opens a brand-new connection to [route], bypassing the idle set. */
    suspend fun openFresh(route: RouteKey): ClientConnection = ClientConnection.open(engine, route)

    /**
     * Returns [connection] to the idle set for its route if [reusable], the
     * pool is open, the connection is still live, and there is room under
     * [PoolConfig.maxIdleConnectionsPerRoute]; otherwise closes it.
     */
    suspend fun release(connection: ClientConnection, reusable: Boolean) {
        val pool = mutex.withLock {
            if (closed || !reusable || !connection.isActive) return@withLock false
            val deque = idle.getOrPut(connection.route) { ArrayDeque() }
            if (deque.size >= config.maxIdleConnectionsPerRoute) return@withLock false
            deque.addLast(Idle(connection, timeSource.markNow()))
            true
        }
        if (!pool) connection.close()
    }

    /**
     * Closes every idle connection and rejects further [lease]s. Idempotent.
     * The caller-owned engine is not closed.
     *
     * A request that has already leased a connection is allowed to finish
     * (it may even open a fresh connection for its retry); its connection is
     * closed on [release] once the pool is closed. Only new leases are rejected.
     */
    suspend fun close() {
        val all = mutex.withLock {
            closed = true
            val snapshot = idle.values.flatMap { deque -> deque.map { it.connection } }
            idle.clear()
            snapshot
        }
        all.forEach { it.close() }
    }

    private fun isUsable(entry: Idle): Boolean {
        if (!entry.connection.isActive) return false
        val timeoutMillis = config.idleTimeoutMillis
        return timeoutMillis <= 0L || entry.idleSince.elapsedNow().inWholeMilliseconds < timeoutMillis
    }
}

/** The outcome of [ConnectionPool.lease]: the connection and whether it was reused from the pool. */
internal class Lease(val connection: ClientConnection, val reused: Boolean)
