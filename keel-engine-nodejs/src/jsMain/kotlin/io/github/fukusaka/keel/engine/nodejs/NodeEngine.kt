package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.Server as KeelServer
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import io.github.fukusaka.keel.core.Channel as KeelChannel

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
 * ```
 * NodeEngine (Node.js net module)
 *   |
 *   +-- bind() ---------> NodeServer (Channel mode: accept -> suspend I/O)
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
) : StreamEngine {

    private val logger = config.loggerFactory.logger("NodeEngine")
    private var closed = false

    override suspend fun bind(host: String, port: Int): KeelServer {
        check(!closed) { "Engine is closed" }

        return suspendCoroutine { cont ->
            val srv = Net.createServer { _ ->
                // No-op: connections handled via "connection" event below
            }

            srv.listen(port) {
                val addr = srv.address()
                val assignedPort = addr.port as Int
                val localAddr = SocketAddress(host, assignedPort)
                val serverChannel = NodeServer(
                    srv, localAddr, config.allocator, config.loggerFactory,
                )

                // Wire connection events to the ServerChannel's accept queue
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
     * @return A [PipelinedServer] that closes the listener when closed.
     * @throws IllegalArgumentException if port is 0 (ephemeral port not supported).
     */
    override fun bindPipeline(
        host: String,
        port: Int,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }
        require(port > 0) {
            "Ephemeral port (port=0) is not supported in bindPipeline. " +
                "Node.js assigns the port asynchronously in the listen callback."
        }

        val srv = Net.createServer { _ -> }
        val serverChannel = NodePipelinedServer(srv, SocketAddress(host, port))

        srv.on("connection") { socket: dynamic ->
            val typedSocket = socket.unsafeCast<Socket>()
            val remoteAddr = typedSocket.remoteAddress?.let { h ->
                typedSocket.remotePort?.let { p -> SocketAddress(h, p) }
            }
            val channelLogger = config.loggerFactory.logger("NodePipelinedChannel")
            val channel = NodePipelinedChannel(
                typedSocket, config.allocator, remoteAddr, null, channelLogger,
            )
            pipelineInitializer(channel)
            channel.armRead()
        }

        srv.listen(port) {
            val addr = srv.address()
            val assignedPort = addr.port as Int
            serverChannel.updateLocalAddress(SocketAddress(host, assignedPort))
            logger.debug { "Pipeline bound to $host:$assignedPort" }
        }

        return serverChannel
    }

    override suspend fun connect(host: String, port: Int): KeelChannel {
        check(!closed) { "Engine is closed" }

        return suspendCoroutine { cont ->
            val socket = Net.createConnection(port, host)

            socket.once("connect") { _: dynamic ->
                val remoteAddr = SocketAddress(host, port)
                val localAddr = socket.localAddress?.let { h ->
                    socket.localPort?.let { p -> SocketAddress(h, p) }
                }
                val channelLogger = config.loggerFactory.logger("NodePipelinedChannel")
                val channel = NodePipelinedChannel(
                    socket, config.allocator, remoteAddr, localAddr, channelLogger,
                )
                logger.debug { "Connected to $host:$port" }
                cont.resume(channel)
            }

            socket.once("error") { err: dynamic ->
                cont.resumeWithException(Error(err.message as? String ?: "connect error"))
            }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            logger.debug { "Engine closed" }
        }
    }

    /**
     * [PipelinedServer] backed by a Node.js net.Server.
     *
     * Wraps the underlying server for lifecycle management.
     * [localAddress] is updated when the listen callback fires.
     */
    private class NodePipelinedServer(
        private val server: Server,
        private var localAddr: SocketAddress,
    ) : PipelinedServer {
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
