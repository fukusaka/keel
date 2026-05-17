package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.requireIpLiteral
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io.github.fukusaka.keel.server.ServerTlsProvider
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.asPem
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import io.github.fukusaka.keel.core.Channel as KeelChannel
import io.github.fukusaka.keel.core.StreamServer as KeelStreamServer

/**
 * Node.js-based [StreamEngine] implementation for JS.
 *
 * Uses Node.js `net` module for TCP I/O. All operations are
 * callback-based internally, bridged to Kotlin coroutines via
 * [suspendCoroutine].
 *
 * **Push-to-pull bridge**: Node.js's event-driven model (socket.on("data"))
 * is bridged to keel's pull model (suspend read) via [SuspendBridgeHandler]
 * in [NodePipelinedChannel]. See [NodePipelinedChannel] KDoc for details.
 *
 * **I/O ownership invariant**: the Node.js process runs a single
 * libuv-driven event loop on a single JavaScript thread. Every
 * `socket.on(...)` callback, `socket.write` continuation, and coroutine
 * resumption (via `Dispatchers.Unconfined`, which inherits the libuv
 * thread context) runs on that single thread in FIFO order. This
 * matches the "strict single-thread per loop + cross-thread funnel"
 * contract that the POSIX engines (`engine-kqueue` / `engine-epoll` /
 * `engine-nio`) enforce explicitly via
 * `if (inEventLoop()) apply else dispatch(Runnable)`, but the
 * enforcement is upstream-delegated: V8 + libuv guarantee the
 * single-thread semantics at the runtime level, so this engine does
 * not need an application-level funnel — and the JS platform exposes
 * no thread-identity primitive against which we could write a runtime
 * assertion in the first place. See `IoEngine` KDoc for the
 * cross-engine contract.
 *
 * ```
 * NodeEngine (Node.js net module)
 *   |
 *   +-- bind() ---------> NodeStreamServer (Coroutine mode: accept -> suspend I/O)
 *   |                       |
 *   |                       +-- accept() --> NodePipelinedChannel
 *   |
 *   +-- bindPipeline() --> NodePipelinedServer (Pipeline mode: push I/O)
 *   |
 *   +-- connect() -------> NodePipelinedChannel
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
class NodeEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
) : StreamEngine, ServerTlsProvider {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    private val logger = config.loggerFactory.logger("NodeEngine")
    private val channelLogger = config.loggerFactory.logger("NodePipelinedChannel")
    private var closed = false

    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): KeelStreamServer = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): KeelStreamServer {
        check(!closed) { "Engine is closed" }
        rejectAbstractOnNonLinux(address)

        return suspendCoroutine { cont ->
            val srv = Net.createServer { _ -> }

            val listenOpts = js("({})")
            listenOpts.path = address.kernelPath
            listenOpts.backlog = bindConfig.backlog
            srv.listen(listenOpts) {
                val serverChannel = NodeStreamServer(
                    srv,
                    address,
                    config.allocator,
                    bindConfig,
                    channelLogger,
                )
                srv.on("connection") { socket: dynamic ->
                    serverChannel.onConnection(socket as Socket)
                }
                logger.debug { "Bound to $address" }
                cont.resume(serverChannel)
            }

            srv.on("error") { err: dynamic ->
                cont.resumeWithException(Error(err.message as? String ?: "listen error"))
            }
        }
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): KeelStreamServer {
        check(!closed) { "Engine is closed" }

        val host = address.resolveFirst(config.resolver).toCanonicalString()
        val port = address.port
        return suspendCoroutine { cont ->
            val srv = Net.createServer { _ ->
                // No-op: connections handled via "connection" event below
            }

            val listenOpts = js("({})")
            listenOpts.port = port
            listenOpts.backlog = bindConfig.backlog
            srv.listen(listenOpts) {
                val addr = srv.address()
                val assignedPort = addr.port as Int
                val localAddr = InetSocketAddress(host, assignedPort)
                val serverChannel = NodeStreamServer(
                    srv,
                    localAddr,
                    config.allocator,
                    bindConfig,
                    channelLogger,
                )

                // Wire connection events to the StreamServer's accept queue
                srv.on("connection") { socket: dynamic ->
                    serverChannel.onConnection(socket as Socket)
                }

                logger.debug { "Bound to $host:$assignedPort" }
                cont.resume(serverChannel)
            }

            srv.on("error") { err: dynamic ->
                cont.resumeWithException(Error(err.message as? String ?: "listen error"))
            }
        }
    }

    /**
     * Binds a pipeline-based TCP listener on [host]:[port].
     *
     * Creates a Node.js `net.Server` with a connection handler that wraps
     * each accepted connection in a [NodePipelinedChannel] and feeds data
     * through the [PipelinedChannel] pipeline — no coroutine suspension on the
     * request hot path.
     *
     * Non-suspend: Node.js `server.listen()` is async, but for non-zero
     * ports the address is available synchronously after `listen()` returns
     * in the same event loop tick. Ephemeral port (port=0) requires the
     * listen callback, which is not supported in this non-suspend context.
     *
     * @param pipelineInitializer Callback to configure the pipeline for each connection.
     * @return A [PipelinedStreamServer] that closes the listener when closed.
     * @throws IllegalArgumentException if port is 0 (ephemeral port not supported).
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer = when (address) {
        is InetSocketAddress -> bindPipelineInet(address, config, pipelineInitializer)
        is UnixSocketAddress -> bindPipelineUnix(address, config, pipelineInitializer)
    }

    /**
     * Builds a [BindConfig] that terminates TLS at the listener level via
     * Node's `tls.createServer()`.
     *
     * Backs the `connector { tls { } }` `EngineNative` strategy: the
     * returned [TlsServerConfig] has a `null` installer, which
     * [bindPipeline] detects as the request to create a TLS listener
     * rather than configure TLS per connection.
     */
    override fun nativeTlsBindConfig(
        tls: TlsConfig,
        backlog: Int,
        socketOptions: SocketOptions,
    ): BindConfig = TlsServerConfig(tls, installer = null, backlog = backlog, childSocketOptions = socketOptions)

    private fun bindPipelineUnix(
        address: UnixSocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }
        rejectAbstractOnNonLinux(address)

        // Listener-level TLS is TCP-specific — for UDS there is no
        // net.tls equivalent (Node.js `tls.createServer` opens TCP
        // listener under the hood). Fall back to plain net.createServer.
        val srv = Net.createServer { _ -> }
        val serverChannel = NodePipelinedServer(srv, address)

        srv.on("connection") { socket: dynamic ->
            val typedSocket = socket.unsafeCast<Socket>()
            applySocketOptions(typedSocket, config.childSocketOptions)
            val channelLogger = this.channelLogger
            val transport = NodeIoTransport(typedSocket, this.config.allocator)
            val channel = NodePipelinedChannel(
                transport,
                channelLogger,
                address,
                null,
            )
            config.initializeConnection(channel)
            pipelineInitializer(channel)
            transport.readEnabled = true
        }

        val listenOpts = js("({})")
        listenOpts.path = address.kernelPath
        listenOpts.backlog = config.backlog
        srv.listen(listenOpts) {
            logger.debug { "Pipeline bound to $address" }
        }

        return serverChannel
    }

    private fun bindPipelineInet(
        address: InetSocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }

        val host = address.requireIpLiteral()
        val port = address.port
        require(port > 0) {
            "Ephemeral port (port=0) is not supported in bindPipeline. " +
                "Node.js assigns the port asynchronously in the listen callback."
        }

        val srv = createServer(config)
        val serverChannel = NodePipelinedServer(srv, InetSocketAddress(host, port))
        val connectionEvent = serverConnectionEvent(config)

        srv.on(connectionEvent) { socket: dynamic ->
            val typedSocket = socket.unsafeCast<Socket>()
            applySocketOptions(typedSocket, config.childSocketOptions)
            val remoteAddr = typedSocket.remoteAddress?.let { h ->
                typedSocket.remotePort?.let { p -> InetSocketAddress(h, p) }
            }
            val channelLogger = this.channelLogger
            val transport = NodeIoTransport(typedSocket, this.config.allocator)
            val channel = NodePipelinedChannel(
                transport,
                channelLogger,
                remoteAddr,
                null,
            )
            // Per-connection BindConfig (keel TlsHandler). Listener-level TLS
            // (tls.createServer) is already active at the transport level, so
            // initializeConnection is skipped for listener-level configs.
            if (!isListenerLevelTls(config)) {
                config.initializeConnection(channel)
            }
            pipelineInitializer(channel)
            transport.readEnabled = true
        }

        val listenOpts = js("({})")
        listenOpts.port = port
        listenOpts.backlog = config.backlog
        srv.listen(listenOpts) {
            val addr = srv.address()
            val assignedPort = addr.port as Int
            serverChannel.updateLocalAddress(InetSocketAddress(host, assignedPort))
            logger.debug { "Pipeline bound to $host:$assignedPort" }
        }

        return serverChannel
    }

    override suspend fun connect(address: SocketAddress): KeelChannel = connect(address, ConnectConfig.DEFAULT)

    override suspend fun connect(address: SocketAddress, config: ConnectConfig): KeelChannel = when (address) {
        is InetSocketAddress -> connectInet(address, config.socketOptions)
        is UnixSocketAddress -> connectUnix(address, config.socketOptions)
    }

    private suspend fun connectUnix(address: UnixSocketAddress, socketOptions: SocketOptions): KeelChannel {
        check(!closed) { "Engine is closed" }
        rejectAbstractOnNonLinux(address)

        return suspendCoroutine { cont ->
            val connectOpts = js("({})")
            connectOpts.path = address.kernelPath
            val socket = Net.createConnection(connectOpts)
            applySocketOptions(socket, socketOptions)

            socket.once("connect") { _: dynamic ->
                val channelLogger = this@NodeEngine.channelLogger
                val transport = NodeIoTransport(socket, config.allocator)
                val channel = NodePipelinedChannel(
                    transport,
                    channelLogger,
                    address,
                    null,
                )
                logger.debug { "Connected to $address" }
                cont.resume(channel)
            }

            socket.once("error") { err: dynamic ->
                cont.resumeWithException(Error(err.message as? String ?: "connect error"))
            }
        }
    }

    /**
     * Linux abstract-namespace Unix sockets are only implemented by
     * Linux kernels. Node.js silently fails on macOS / Windows when
     * the `path` starts with `\u0000`; surface that as an explicit
     * engine-level error instead of letting the runtime produce an
     * opaque `ENOENT`.
     */
    private fun rejectAbstractOnNonLinux(address: UnixSocketAddress) {
        if (address.isAbstract) {
            val platform = js("process.platform") as String
            if (platform != "linux") {
                throw UnsupportedOperationException(
                    "NodeEngine abstract-namespace Unix sockets require Linux " +
                        "(got platform '$platform'): $address",
                )
            }
        }
    }

    private suspend fun connectInet(address: InetSocketAddress, socketOptions: SocketOptions): KeelChannel {
        check(!closed) { "Engine is closed" }

        val host = address.resolveFirst(config.resolver).toCanonicalString()
        val port = address.port
        return suspendCoroutine { cont ->
            val socket = Net.createConnection(port, host)
            applySocketOptions(socket, socketOptions)

            socket.once("connect") { _: dynamic ->
                val remoteAddr = InetSocketAddress(host, port)
                val localAddr = socket.localAddress?.let { h ->
                    socket.localPort?.let { p -> InetSocketAddress(h, p) }
                }
                val channelLogger = this@NodeEngine.channelLogger
                val transport = NodeIoTransport(socket, config.allocator)
                val channel = NodePipelinedChannel(
                    transport,
                    channelLogger,
                    remoteAddr,
                    localAddr,
                )
                logger.debug { "Connected to $host:$port" }
                cont.resume(channel)
            }

            socket.once("error") { err: dynamic ->
                cont.resumeWithException(Error(err.message as? String ?: "connect error"))
            }
        }
    }

    /**
     * Closes the engine: cancels every child coroutine launched on this
     * engine's scope and joins their completion. Node.js is single-threaded
     * and cooperative, so no dispatcher thread shutdown is required beyond
     * cancelling in-flight work.
     *
     * Idempotent.
     */
    override suspend fun close() {
        if (!closed) {
            closed = true
            coroutineContext.job.cancelAndJoin()
            logger.debug { "Engine closed" }
        }
    }

    /**
     * Creates a server based on the bind configuration.
     *
     * When [config] is a [TlsServerConfig] with a non-[TlsCodecFactory]
     * installer, creates a `tls.createServer()` for transport-level TLS.
     * Otherwise creates a plain `net.createServer()`.
     */
    private fun createServer(config: BindConfig): Server {
        if (isListenerLevelTls(config)) {
            val tlsConfig = config as TlsServerConfig
            val certs = requireNotNull(tlsConfig.tls.certificates) {
                "Node.js listener-level TLS requires certificates"
            }.asPem()
            val options = js("{}")
            options.key = certs.privateKeyPem
            options.cert = certs.certificatePem
            return Tls.createServer(options) { _ -> }
        }
        return Net.createServer { _ -> }
    }

    /**
     * Returns the connection event name based on TLS mode.
     *
     * `tls.Server` fires `"secureConnection"` (after TLS handshake)
     * instead of `"connection"` (plain TCP).
     */
    private fun serverConnectionEvent(config: BindConfig): String =
        if (isListenerLevelTls(config)) "secureConnection" else "connection"

    /**
     * Detects if the config requests engine-native (listener-level) TLS.
     *
     * [TlsServerConfig] with `installer == null` means the engine should
     * handle TLS at the listener level via `tls.createServer()`. Non-null
     * installer means per-connection TLS via [initializeConnection].
     */
    private fun isListenerLevelTls(config: BindConfig): Boolean {
        return config is TlsServerConfig && config.installer == null
    }

    /**
     * [PipelinedStreamServer] backed by a Node.js net.Server.
     *
     * Wraps the underlying server for lifecycle management.
     * [localAddress] is updated when the listen callback fires.
     */
    private class NodePipelinedServer(
        private val server: Server,
        private var localAddr: SocketAddress,
    ) : PipelinedStreamServer {
        private var _active = true

        override val localAddress: SocketAddress get() = localAddr
        override val isActive: Boolean get() = _active

        /** Updates the local address after listen completes. */
        internal fun updateLocalAddress(addr: SocketAddress) {
            localAddr = addr
        }

        override fun close() {
            if (_active) {
                _active = false
                server.close()
            }
        }
    }
}
