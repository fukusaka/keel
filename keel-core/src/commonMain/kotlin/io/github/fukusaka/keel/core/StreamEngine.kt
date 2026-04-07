package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.ChannelPipeline

/**
 * Byte-stream I/O engine for connection-oriented transports (TCP, Unix SOCK_STREAM).
 *
 * Each engine module provides its own implementation:
 * epoll (Linux), kqueue (macOS), NIO (JVM), Netty (JVM),
 * Node.js net (JS), NWConnection (Apple), io_uring (Linux).
 *
 * ```
 * Application
 *       |
 *   StreamEngine  (bind / connect)
 *       |
 *   +---+---+---+---+---+---+---+
 *   |   |   |   |   |   |   |   |
 *  kq  ep  nio net  nw  js  uring
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
 *
 * @see IoEngine
 */
interface StreamEngine : IoEngine {

    /**
     * Binds a server socket and starts listening for connections.
     *
     * Internally performs: socket -> bind -> listen.
     *
     * @param host Bind address (e.g. "0.0.0.0" for all interfaces, "127.0.0.1" for loopback).
     * @param port Port number. 0 lets the OS assign an ephemeral port.
     * @return a [Server] that accepts incoming connections.
     */
    suspend fun bind(host: String, port: Int): Server

    /**
     * Binds a server socket with Pipeline-mode connection handling.
     *
     * Each accepted connection is configured via [pipelineInitializer],
     * which installs handlers into the connection's
     * [ChannelPipeline].
     * The engine drives I/O via callbacks — no coroutine context required.
     *
     * Non-suspend: Pipeline mode avoids coroutine overhead at bind time.
     * Engines that require async listener startup (e.g., NWConnection)
     * block internally until the listener is ready.
     *
     * @param host Bind address (e.g. "0.0.0.0" for all interfaces).
     * @param port Port number. 0 lets the OS assign an ephemeral port.
     * @param pipelineInitializer Callback to configure the pipeline for each accepted connection.
     * @return a [PipelinedServer] for lifecycle management.
     * @throws UnsupportedOperationException if this engine does not support pipeline mode.
     */
    fun bindPipeline(
        host: String,
        port: Int,
        pipelineInitializer: (ChannelPipeline) -> Unit,
    ): PipelinedServer {
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support pipeline mode",
        )
    }

    /**
     * Opens an outbound connection to a remote peer.
     *
     * @param host Remote host (IPv4 literal in Phase (a); DNS resolution deferred).
     * @param port Remote port.
     * @return a [Channel] ready for read/write.
     */
    suspend fun connect(host: String, port: Int): Channel
}
