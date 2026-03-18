package io.github.keel.core

/**
 * A server-side channel that listens for incoming connections.
 */
interface ServerChannel : AutoCloseable {

    /** Local address this server is bound to. */
    val localAddress: SocketAddress

    /** True if the server is listening for connections. */
    val isActive: Boolean

    /**
     * Accepts the next incoming connection.
     * Suspends until a connection is available.
     */
    suspend fun accept(): Channel

    /** Stops listening and releases resources. */
    override fun close()
}
