package io.github.keel.core

/**
 * Platform-agnostic I/O engine.
 *
 * Each engine module (epoll, kqueue, NIO, Netty, Node.js, NWConnection)
 * provides its own implementation.
 */
interface IoEngine : AutoCloseable {

    /**
     * Binds a server socket and starts listening for connections.
     *
     * @return a [ServerChannel] that accepts incoming connections.
     */
    suspend fun bind(host: String, port: Int): ServerChannel

    /**
     * Opens an outbound connection to a remote peer.
     *
     * @return a [Channel] ready for read/write.
     */
    suspend fun connect(host: String, port: Int): Channel

    /** Closes the engine and releases all resources. */
    override fun close()
}
