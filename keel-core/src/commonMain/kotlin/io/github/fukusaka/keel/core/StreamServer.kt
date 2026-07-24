package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * A server that listens for incoming connections.
 *
 * Created by [StreamEngine.bind]. Each call to [accept] suspends until
 * a new client connects, then returns a [Channel] for that connection.
 *
 * ```
 * val server = engine.bind("0.0.0.0", 8080)
 * while (server.isActive) {
 *     val conn = server.accept()
 *     launch { handleConnection(conn) }
 * }
 * ```
 *
 * A `connections(): Flow<Channel>` convenience is intentionally omitted.
 * Callers who need Flow semantics can write an extension function wrapping
 * the accept loop above.
 */
interface StreamServer : AutoCloseable {

    /** Local address this server is bound to. */
    val localAddress: SocketAddress

    /** True if the server is listening for connections. */
    val isActive: Boolean

    /**
     * Accepts the next incoming connection.
     * Suspends until a connection is available.
     *
     * [BindConfig.initializeConnection] is called automatically on the
     * accepted channel before it is returned to the caller.
     *
     * @return a [PipelinedChannel] for the accepted connection.
     */
    suspend fun accept(): PipelinedChannel

    /**
     * Stops listening and releases the server socket.
     *
     * **The listening port is released asynchronously** — see
     * [PipelinedStreamServer.close][io.github.fukusaka.keel.pipeline.PipelinedStreamServer.close]
     * for the full contract. Suspended [accept] callers are resumed with a
     * cancellation before the socket goes away.
     *
     * Idempotent.
     */
    override fun close()
}
