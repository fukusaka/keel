package io.github.fukusaka.keel.server.http

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Registry of live server connections, consulted by [KeelHttpServer.stop]
 * to drive a graceful shutdown.
 *
 * Each accepted connection's [HttpServerHandler] registers itself when the
 * channel becomes active and deregisters when it goes inactive. Both
 * happen from the connection's EventLoop thread — and a server running
 * more than one EventLoop registers from several threads at once — so the
 * backing set is guarded by a [Mutex]; the handler issues every mutation
 * from a coroutine. Connection setup / teardown is not a per-request hot
 * path, so a lock here is acceptable.
 *
 * A single instance is shared by one [KeelHttpServer] across its
 * `start()` / `stop()` cycles; [clear] empties it so a restarted server
 * does not retain handlers from the previous run.
 */
internal class ServerConnections {

    private val mutex = Mutex()
    private val handlers = mutableSetOf<HttpServerHandler>()

    /** Adds [handler] to the registry. */
    suspend fun register(handler: HttpServerHandler) {
        mutex.withLock { handlers.add(handler) }
    }

    /** Removes [handler] from the registry. */
    suspend fun unregister(handler: HttpServerHandler) {
        mutex.withLock { handlers.remove(handler) }
    }

    /** A point-in-time copy of the registered connections. */
    suspend fun snapshot(): List<HttpServerHandler> =
        mutex.withLock { handlers.toList() }

    /** Drops every registered connection. */
    suspend fun clear() {
        mutex.withLock { handlers.clear() }
    }
}
