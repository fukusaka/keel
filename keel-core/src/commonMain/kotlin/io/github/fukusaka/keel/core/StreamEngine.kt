package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer

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
 * IP literals and reject hostnames (a future change will add
 * `getaddrinfo`-based resolution). [UnixSocketAddress] is not yet
 * supported by any engine and throws [UnsupportedOperationException]
 * until a future change adds the support.
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
     * May be called several times: every server returned by this engine
     * shares its event loops and allocator (see the resource-sharing
     * invariant on [IoEngine]); separate calls carry no atomicity
     * between them.
     *
     * @param address Bind endpoint. For [InetSocketAddress], hostnames
     *   are resolved via [IoEngineConfig.resolver]. [UnixSocketAddress]
     *   throws [UnsupportedOperationException] until a future change
     *   adds the support.
     * @param bindConfig Per-server bind configuration (backlog, etc.).
     * @return a [StreamServer] that accepts incoming connections.
     */
    suspend fun bind(address: SocketAddress, bindConfig: BindConfig = BindConfig()): StreamServer

    /**
     * Convenience overload: builds an [InetSocketAddress] from [host]
     * and [port]. IP literals in [host] are parsed eagerly; hostnames
     * are resolved lazily when the engine consumes the address.
     */
    suspend fun bind(host: String, port: Int, bindConfig: BindConfig = BindConfig()): StreamServer =
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
     * May be called several times: every server returned by this engine
     * shares its event loops and allocator (see the resource-sharing
     * invariant on [IoEngine]); separate calls carry no atomicity
     * between them — for one server on several addresses, use the
     * list-taking overload, whose bind is all-or-nothing.
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
     * @return a [PipelinedStreamServer] for lifecycle management.
     * @throws UnsupportedOperationException if this engine does not support pipeline mode.
     */
    fun bindPipeline(
        address: SocketAddress,
        config: BindConfig = BindConfig(),
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
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
    ): PipelinedStreamServer = bindPipeline(InetSocketAddress(host, port), config, pipelineInitializer)

    /**
     * Binds one server that listens on several addresses at once, with
     * Pipeline-mode connection handling.
     *
     * The bind is all-or-nothing: either every entry of [binds] is bound
     * and one [PipelinedStreamServer] owning all the listeners is
     * returned, or — when any bind fails — every listener bound so far is
     * closed again and the failure is rethrown.
     *
     * [pipelineInitializer] is shared by every address: a multi-address
     * server is one application reachable through several doors.
     * Per-address differences are expressed through each entry's
     * [BindSpec.config] (e.g. a TLS config on the TLS port only), not
     * through per-address handler chains — an application that differs
     * per address is a different server (bind it separately).
     *
     * A single-entry list behaves exactly like the single-address
     * [bindPipeline] overload.
     *
     * @param binds Listen endpoints, each with its own per-address
     *   config, bound in list order. Must not be empty.
     * @param pipelineInitializer Callback to configure the channel for
     *   each accepted connection, regardless of the address it arrived on.
     * @return a [PipelinedStreamServer] whose
     *   [PipelinedStreamServer.localAddresses] lists every bound address
     *   in [binds] order.
     * @throws IllegalArgumentException if [binds] is empty.
     * @throws UnsupportedOperationException if this engine does not
     *   support pipeline mode or multi-address binding (engines adopt
     *   this overload individually).
     */
    fun bindPipeline(
        binds: List<BindSpec>,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        require(binds.isNotEmpty()) { "binds must not be empty" }
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support multi-address pipeline mode",
        )
    }

    /**
     * Opens an outbound connection to a remote peer.
     *
     * @param address Remote endpoint. Hostnames are resolved via
     *   [IoEngineConfig.resolver]; IP literals are used directly.
     * @return a [Channel] ready for read/write.
     */
    suspend fun connect(address: SocketAddress): Channel

    /**
     * Opens an outbound connection with per-connect configuration
     * (e.g., socket options).
     *
     * Default implementation delegates to [connect] and throws
     * [UnsupportedOperationException] if [config] carries any
     * non-default socket options — signals that the engine does not
     * yet support per-connect configuration and the caller's options
     * would otherwise be silently ignored. Engines that support
     * configuration override this method.
     *
     * @param address Remote endpoint (same semantics as [connect]).
     * @param config Per-connect configuration including socket options.
     */
    suspend fun connect(address: SocketAddress, config: ConnectConfig): Channel {
        if (!config.socketOptions.isEmpty) {
            throw UnsupportedOperationException(
                "${this::class.simpleName} does not support socket options " +
                    "via ConnectConfig — got $config",
            )
        }
        return connect(address)
    }

    /**
     * Convenience overload: connect to `host:port`.
     */
    suspend fun connect(host: String, port: Int): Channel =
        connect(InetSocketAddress(host, port))
}
