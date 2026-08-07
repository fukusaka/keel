package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.bindAllOrRollback
import io.github.fukusaka.keel.core.requireIpLiteral
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.guarded
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io.github.fukusaka.keel.server.ServerTlsProvider
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
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

    /**
     * The configured factory, wrapped so no log statement in this engine can
     * throw. Read once here rather than at each use; see `guarded`.
     */
    private val guardedLoggerFactory = config.loggerFactory.guarded()

    private val logger = guardedLoggerFactory.logger("NodeEngine")
    private val channelLogger = guardedLoggerFactory.logger("NodePipelinedChannel")
    private var closed = false

    /**
     * Engine-owned allocator child. Node.js runs a single libuv event
     * loop, so the engine takes one [BufferAllocator.createChild] off
     * the user-passed [config].allocator and routes every connection's
     * buffers through it. The platform default ([DefaultAllocator] on
     * JS) is stateless and its `createChild()` returns `this`, so the
     * indirection is free at runtime; a user-passed pooled allocator
     * (e.g. via a future JS pool backend) is properly engine-owned and
     * gets `close()`d on teardown. The parent stays borrowed.
     */
    private val allocator: BufferAllocator = config.allocator.createChild()

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
                    allocator,
                    bindConfig,
                    channelLogger,
                    config.idleTimeoutMillis,
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
                    allocator,
                    bindConfig,
                    channelLogger,
                    config.idleTimeoutMillis,
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
    ): PipelinedStreamServer = bindPipeline(listOf(BindSpec(address, config)), pipelineInitializer)

    /**
     * Multi-address pipeline bind: every entry of [binds] becomes one
     * `net.Server` of a single [NodePipelinedServer] (Node's own model is
     * strictly one listen per server instance). All-or-nothing for the
     * failures this engine can observe synchronously — argument validation
     * (the ephemeral-port rejection) and server construction; an
     * asynchronous listen failure (e.g. a port conflict surfacing in the
     * `error` event after this method returned) follows the engine's
     * existing single-address semantics, because Node assigns bind results
     * asynchronously and this non-suspend method cannot await them.
     */
    override fun bindPipeline(
        binds: List<BindSpec>,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }
        val listeners = bindAllOrRollback(
            binds = binds,
            logger = logger,
            closeOne = { listener: NodePipelinedServer.Listener ->
                listener.server.close()
            },
        ) { spec ->
            when (val address = spec.address) {
                is InetSocketAddress -> openPipelineInetListener(address, spec.config, pipelineInitializer)
                is UnixSocketAddress -> openPipelineUnixListener(address, spec.config, pipelineInitializer)
            }
        }
        return NodePipelinedServer(listeners)
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

    private fun openPipelineUnixListener(
        address: UnixSocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): NodePipelinedServer.Listener {
        rejectAbstractOnNonLinux(address)

        // Listener-level TLS is TCP-specific — for UDS there is no
        // net.tls equivalent (Node.js `tls.createServer` opens TCP
        // listener under the hood). Fall back to plain net.createServer.
        val srv = Net.createServer { _ -> }

        srv.on("connection") { socket: dynamic ->
            val typedSocket = socket.unsafeCast<Socket>()
            applySocketOptions(typedSocket, config.childSocketOptions)
            val channelLogger = this.channelLogger
            val transport = NodeIoTransport(
                typedSocket,
                this.allocator,
                idleTimeoutMillis = effectiveIdleTimeout(config.idleTimeoutMillis),
                flushCoalescing = this@NodeEngine.config.flushCoalescing,
            )
            // The UDS listener path is the accepted socket's local address
            // by definition.
            val channel = NodePipelinedChannel(
                transport,
                channelLogger,
                address,
                address,
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

        return NodePipelinedServer.Listener(srv, address)
    }

    private fun openPipelineInetListener(
        address: InetSocketAddress,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): NodePipelinedServer.Listener {
        val host = address.requireIpLiteral()
        val port = address.port
        require(port > 0) {
            "Ephemeral port (port=0) is not supported in bindPipeline. " +
                "Node.js assigns the port asynchronously in the listen callback."
        }

        // With the ephemeral port rejected above, the requested address IS
        // the bound address — no post-listen fixup needed.
        val localAddr = InetSocketAddress(host, port)
        val srv = createServer(config)
        val connectionEvent = serverConnectionEvent(config)

        srv.on(connectionEvent) { socket: dynamic ->
            val typedSocket = socket.unsafeCast<Socket>()
            applySocketOptions(typedSocket, config.childSocketOptions)
            val remoteAddr = typedSocket.remoteAddress?.let { h ->
                typedSocket.remotePort?.let { p -> InetSocketAddress(h, p) }
            }
            // The accepted socket's own local endpoint (concrete interface
            // address under a wildcard bind) — lets a shared pipeline
            // initializer branch on the listening address; the listener
            // address is the fallback when the socket properties are
            // already gone.
            val channelLocal = typedSocket.localAddress?.let { h ->
                typedSocket.localPort?.let { p -> InetSocketAddress(h, p) }
            } ?: localAddr
            val channelLogger = this.channelLogger
            val transport = NodeIoTransport(
                typedSocket,
                this.allocator,
                idleTimeoutMillis = effectiveIdleTimeout(config.idleTimeoutMillis),
                flushCoalescing = this@NodeEngine.config.flushCoalescing,
            )
            val channel = NodePipelinedChannel(
                transport,
                channelLogger,
                remoteAddr,
                channelLocal,
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
            logger.debug { "Pipeline bound to $host:$port" }
        }

        return NodePipelinedServer.Listener(srv, localAddr)
    }

    override suspend fun connect(address: SocketAddress): KeelChannel = connect(address, ConnectConfig.DEFAULT)

    override suspend fun connect(address: SocketAddress, config: ConnectConfig): KeelChannel = when (address) {
        is InetSocketAddress -> connectInet(address, config.socketOptions, config.idleTimeoutMillis)
        is UnixSocketAddress -> connectUnix(address, config.socketOptions, config.idleTimeoutMillis)
    }

    /**
     * Effective per-connection idle timeout: the per-server / per-client override
     * when present, else the engine-wide [IoEngineConfig.idleTimeoutMillis].
     */
    private fun effectiveIdleTimeout(override: Long?): Long = override ?: config.idleTimeoutMillis

    private suspend fun connectUnix(
        address: UnixSocketAddress,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): KeelChannel {
        check(!closed) { "Engine is closed" }
        rejectAbstractOnNonLinux(address)

        return suspendCoroutine { cont ->
            val connectOpts = js("({})")
            connectOpts.path = address.kernelPath
            val socket = Net.createConnection(connectOpts)
            applySocketOptions(socket, socketOptions)

            socket.once("connect") { _: dynamic ->
                val channelLogger = this@NodeEngine.channelLogger
                val transport = NodeIoTransport(
                    socket,
                    allocator,
                    idleTimeoutMillis = effectiveIdleTimeout(idleTimeoutOverride),
                    flushCoalescing = this@NodeEngine.config.flushCoalescing,
                )
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

    private suspend fun connectInet(
        address: InetSocketAddress,
        socketOptions: SocketOptions,
        idleTimeoutOverride: Long?,
    ): KeelChannel {
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
                val transport = NodeIoTransport(
                    socket,
                    allocator,
                    idleTimeoutMillis = effectiveIdleTimeout(idleTimeoutOverride),
                    flushCoalescing = this@NodeEngine.config.flushCoalescing,
                )
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
            // Close the engine-owned allocator child. Node.js runs on a
            // single libuv thread; the SupervisorJob cancel above
            // guarantees every coroutine that touched the allocator has
            // finished before we drain its pool. The user-passed parent
            // (`config.allocator`) stays borrowed.
            allocator.close()
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
            val options = NodeTlsOptions.build(tlsConfig.tls)
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
    /**
     * Pipeline server wrapping one `net.Server` per bound address (Node's
     * own model is strictly one listen per server instance).
     *
     * [close] initiates every server's close; Node stops accepting and
     * releases each listen socket asynchronously on the event loop, so the
     * port release is prompt but not synchronous with close() returning.
     */
    internal class NodePipelinedServer(
        private val listeners: List<Listener>,
    ) : PipelinedStreamServer {

        init {
            require(listeners.isNotEmpty()) { "listeners must not be empty" }
        }

        private var _active = true

        override val localAddress: SocketAddress get() = listeners.first().localAddress
        override val localAddresses: List<SocketAddress> get() = listeners.map { it.localAddress }
        override val isActive: Boolean get() = _active

        override fun close() {
            if (!_active) return
            _active = false
            for (listener in listeners) {
                listener.server.close()
            }
        }

        /** One bound listen address: its `net.Server` and resolved address. */
        internal class Listener(
            val server: Server,
            val localAddress: SocketAddress,
        )
    }
}
