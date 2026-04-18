package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.PipelinedChannel

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
 * Addresses are passed as [SocketAddress]. IP literal hosts are
 * consumed directly; hostnames (`Host.Name`) are resolved at call time
 * via [IoEngineConfig.resolver]. Native engines currently support only
 * IP literals and reject hostnames (Phase 11 PR B will add
 * `getaddrinfo`). [UnixSocketAddress] is not yet supported by any
 * engine and throws [UnsupportedOperationException] until Phase 11 PR C.
 *
 * Convenience overloads accepting `host: String, port: Int` are
 * provided as default interface members so existing call sites
 * compile without explicit construction of an [InetSocketAddress].
 *
 * @see IoEngine
 */
interface StreamEngine : IoEngine {

    /**
     * Binds a server socket and starts listening for connections.
     *
     * Internally performs: socket -> bind -> listen.
     *
     * @param address Bind endpoint. For [InetSocketAddress], hostnames
     *   are resolved via [IoEngineConfig.resolver]. [UnixSocketAddress]
     *   throws [UnsupportedOperationException] until Phase 11 PR C.
     * @param bindConfig Per-server bind configuration (backlog, etc.).
     * @return a [Server] that accepts incoming connections.
     */
    suspend fun bind(address: SocketAddress, bindConfig: BindConfig = BindConfig()): Server

    /**
     * Convenience overload: builds an [InetSocketAddress] from [host]
     * and [port]. IP literals in [host] are parsed eagerly; hostnames
     * are resolved lazily when the engine consumes the address.
     */
    suspend fun bind(host: String, port: Int, bindConfig: BindConfig = BindConfig()): Server =
        bind(InetSocketAddress(host, port), bindConfig)

    /**
     * Binds a server socket with Pipeline-mode connection handling.
     *
     * Each accepted connection is configured via [pipelineInitializer],
     * which receives the [PipelinedChannel] for pipeline handler setup.
     * The engine drives I/O via callbacks — no coroutine context required.
     *
     * When [config] is provided, the engine calls
     * [BindConfig.initializeConnection] per-connection before
     * [pipelineInitializer]. Listener-level engines (e.g., Node.js,
     * NWConnection) may inspect [config] at listener creation time for
     * transport-level TLS setup.
     *
     * Non-suspend: Pipeline mode avoids coroutine overhead at bind time.
     * Engines that require async listener startup (e.g., NWConnection)
     * block internally until the listener is ready. Because this path
     * cannot invoke a suspending resolver, [InetSocketAddress] hosts
     * must be IP literals ([Host.Ip]); hostnames throw
     * [UnsupportedOperationException].
     *
     * @param address Bind endpoint. Hostnames are rejected (see above).
     * @param config Per-server bind configuration (backlog, TLS via subclass).
     * @param pipelineInitializer Callback to configure the channel for each accepted connection.
     *        Receives the [PipelinedChannel] for pipeline handler setup.
     * @return a [PipelinedServer] for lifecycle management.
     * @throws UnsupportedOperationException if this engine does not support pipeline mode.
     */
    fun bindPipeline(
        address: SocketAddress,
        config: BindConfig = BindConfig(),
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedServer {
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support pipeline mode",
        )
    }

    /**
     * Convenience overload: pipeline-mode bind to `host:port`.
     */
    fun bindPipeline(
        host: String,
        port: Int,
        config: BindConfig = BindConfig(),
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedServer = bindPipeline(InetSocketAddress(host, port), config, pipelineInitializer)

    /**
     * Opens an outbound connection to a remote peer.
     *
     * @param address Remote endpoint. Hostnames are resolved via
     *   [IoEngineConfig.resolver]; IP literals are used directly.
     * @return a [Channel] ready for read/write.
     */
    suspend fun connect(address: SocketAddress): Channel

    /**
     * Convenience overload: connect to `host:port`.
     */
    suspend fun connect(host: String, port: Int): Channel =
        connect(InetSocketAddress(host, port))
}
