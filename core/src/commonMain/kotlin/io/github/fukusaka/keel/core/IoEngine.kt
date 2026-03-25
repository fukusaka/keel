package io.github.fukusaka.keel.core

/**
 * Platform-agnostic I/O engine interface.
 *
 * Each engine module provides its own implementation:
 * epoll (Linux), kqueue (macOS), NIO (JVM), Netty (JVM),
 * Node.js net (JS), NWConnection (Apple).
 *
 * ```
 * Application
 *       |
 *   IoEngine  (bind / connect)
 *       |
 *   +---+---+---+---+---+---+
 *   |   |   |   |   |   |   |
 *  kq  ep  nio net  nw  js  (io_uring: Phase 8)
 * ```
 *
 * [bind] and [connect] are suspend functions because some engines
 * (Netty, NWConnection, Node.js) perform asynchronous setup internally.
 * Synchronous engines (kqueue, epoll, NIO) return immediately.
 *
 * [bind] combines POSIX `bind()` + `listen()` into a single operation,
 * consistent with Go net.Listen, tokio TcpListener::bind, Swift NIO
 * ServerBootstrap.bind, and Ktor CIO. There is no use case for binding
 * without listening in keel's scope.
 */
interface IoEngine : AutoCloseable {

    /**
     * Binds a server socket and starts listening for connections.
     *
     * Internally performs: socket -> bind -> listen.
     *
     * @param host Bind address (e.g. "0.0.0.0" for all interfaces, "127.0.0.1" for loopback).
     * @param port Port number. 0 lets the OS assign an ephemeral port.
     * @return a [ServerChannel] that accepts incoming connections.
     */
    suspend fun bind(host: String, port: Int): ServerChannel

    /**
     * Opens an outbound connection to a remote peer.
     *
     * @param host Remote host (IPv4 literal in Phase (a); DNS resolution deferred).
     * @param port Remote port.
     * @return a [Channel] ready for read/write.
     */
    suspend fun connect(host: String, port: Int): Channel

    /** Closes the engine and releases all resources (kqueue fd, selector, etc.). */
    override fun close()
}
