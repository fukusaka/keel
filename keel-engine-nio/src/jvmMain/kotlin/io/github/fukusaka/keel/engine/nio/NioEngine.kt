package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.connectWithFallback
import io.github.fukusaka.keel.core.requireFilesystemOnly
import io.github.fukusaka.keel.core.requireIpLiteral
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.debug
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import java.net.InetSocketAddress as JavaInetSocketAddress

/**
 * JVM NIO-based [StreamEngine] implementation with multi-threaded EventLoop.
 *
 * Uses a boss/worker EventLoop model (same as Netty):
 * - **Boss EventLoop**: handles `accept()` on the ServerSocketChannel
 * - **Worker EventLoopGroup**: handles `read`/`write`/`flush` on accepted channels
 *
 * New connections are assigned to worker EventLoops in round-robin order.
 * Each worker thread runs its own [java.nio.channels.Selector] and acts as
 * a [CoroutineDispatcher][kotlinx.coroutines.CoroutineDispatcher], so all
 * I/O + request processing for a channel runs on a single thread without
 * cross-thread dispatch.
 *
 * **SelectionKey caching**: Channels are registered with the Selector once
 * via [NioEventLoop.registerChannel]. Subsequent I/O uses
 * [NioEventLoop.setInterestCallback] to toggle interest ops without JNI
 * re-registration.
 *
 * ```
 * NioEngine
 *   |
 *   +-- bossLoop (accept EventLoop)
 *   |     |
 *   |     +-- bind() → NioServer (cached SelectionKey)
 *   |           |
 *   |           +-- accept() → registerChannel on workerLoop → NioPipelinedChannel
 *   |
 *   +-- workerGroup (N worker EventLoops, round-robin)
 *         |
 *         +-- worker[0]: Channel A, E, I, ...
 *         +-- worker[1]: Channel B, F, J, ...
 *         +-- worker[N]: ...
 * ```
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] controls
 *               the number of worker EventLoop threads. 0 (default) resolves
 *               to `availableProcessors()`.
 */
class NioEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
) : StreamEngine {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    private val logger = config.loggerFactory.logger("NioEngine")
    private val eventLoopLogger = config.loggerFactory.logger("NioEventLoop")
    private val bossLoop = NioEventLoop("keel-nio-boss", eventLoopLogger)
    private val workerGroup =
        NioEventLoopGroup(
            resolveThreads(config),
            "keel-nio-worker",
            eventLoopLogger,
            config.allocator,
        )
    private var closed = false

    /**
     * Binds a suspend-based server on [host]:[port].
     *
     * Opens a [ServerSocketChannel] in non-blocking mode, registers it with
     * the boss EventLoop's Selector, and returns a [NioServer] whose
     * [accept][NioServer.accept] returns [NioPipelinedChannel] instances.
     *
     * @throws IllegalStateException if the engine is closed.
     */
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): ServerChannel = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("NioEngine does not support abstract-namespace Unix sockets (JVM UnixDomainSocketAddress is filesystem-only)")

        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverChannel.configureBlocking(false)
        serverChannel.bind(UnixDomainSocketAddress.of(Path.of(address.path)), bindConfig.backlog)

        val localAddr = NioPipelinedChannel.toSocketAddress(serverChannel.localAddress) ?: address
        val selectionKey = bossLoop.registerChannel(serverChannel)

        logger.debug { "Bound to $localAddr" }
        return NioServer(serverChannel, selectionKey, bossLoop, workerGroup, localAddr, bindConfig, logger)
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }

        val host = address.resolveFirst(config.resolver).toCanonicalString()
        val port = address.port
        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(JavaInetSocketAddress(host, port), bindConfig.backlog)

        val localAddr = NioPipelinedChannel.toSocketAddress(serverChannel.localAddress)
            ?: error("Failed to get local address")

        // One-time registration with the boss Selector
        val selectionKey = bossLoop.registerChannel(serverChannel)

        logger.debug { "Bound to $localAddr" }
        return NioServer(serverChannel, selectionKey, bossLoop, workerGroup, localAddr, bindConfig, logger)
    }

    /**
     * Creates a TCP client connection (non-blocking).
     *
     * The SocketChannel is opened in non-blocking mode so `connect()`
     * returns false (connection pending). The coroutine then suspends
     * on `OP_CONNECT` via the worker EventLoop until the connection is
     * established. On loopback, `connect()` may return true immediately
     * without needing to suspend.
     *
     * The connected channel is assigned to the next worker EventLoop
     * in round-robin order with a cached [SelectionKey].
     */
    override suspend fun connect(address: SocketAddress): Channel = when (address) {
        is InetSocketAddress -> connectInet(address)
        is UnixSocketAddress -> connectUnix(address)
    }

    private suspend fun connectUnix(address: UnixSocketAddress): Channel {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("NioEngine does not support abstract-namespace Unix sockets (JVM UnixDomainSocketAddress is filesystem-only)")

        val socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX)
        socketChannel.configureBlocking(false)
        val (workerLoop, allocator) = workerGroup.next()

        val connected = try {
            socketChannel.connect(UnixDomainSocketAddress.of(Path.of(address.path)))
        } catch (e: Exception) {
            socketChannel.close()
            throw e
        }

        val selectionKey = workerLoop.registerChannel(socketChannel)

        if (!connected) {
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    workerLoop.setInterestCallback(selectionKey, SelectionKey.OP_CONNECT) {
                        cont.resume(Unit)
                    }
                    cont.invokeOnCancellation {
                        workerLoop.removeInterest(selectionKey, SelectionKey.OP_CONNECT)
                        selectionKey.cancel()
                        runCatching { socketChannel.close() }
                    }
                }
                socketChannel.finishConnect()
            } catch (e: Exception) {
                selectionKey.cancel()
                runCatching { socketChannel.close() }
                throw e
            }
        }

        val remoteAddr = NioPipelinedChannel.toSocketAddress(socketChannel.remoteAddress) ?: address
        val localAddr = NioPipelinedChannel.toSocketAddress(socketChannel.localAddress)

        logger.debug { "Connected to $remoteAddr" }
        val transport = NioIoTransport(socketChannel, selectionKey, workerLoop, allocator)
        return NioPipelinedChannel(transport, logger, remoteAddr, localAddr)
    }

    private suspend fun connectInet(address: InetSocketAddress): Channel {
        check(!closed) { "Engine is closed" }
        return address.connectWithFallback(config.resolver) { ip ->
            connectToIp(ip.toCanonicalString(), address.port)
        }
    }

    private suspend fun connectToIp(host: String, port: Int): Channel {
        val socketChannel = SocketChannel.open()
        socketChannel.configureBlocking(false)
        val (workerLoop, allocator) = workerGroup.next()

        // Try connect first — loopback may succeed or fail immediately
        // without needing Selector registration.
        val connected = try {
            socketChannel.connect(JavaInetSocketAddress(host, port))
        } catch (e: Exception) {
            socketChannel.close()
            throw e
        }

        // One-time registration with the worker Selector
        val selectionKey = workerLoop.registerChannel(socketChannel)

        if (!connected) {
            // Connection in progress — suspend until OP_CONNECT fires.
            // Attach a plain Runnable (not the continuation) to avoid the
            // CancellableContinuationImpl-as-Runnable trap in
            // NioEventLoop.processSelectedKeys — see NioServer KDoc for the
            // full rationale.
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    workerLoop.setInterestCallback(selectionKey, SelectionKey.OP_CONNECT) {
                        cont.resume(Unit)
                    }
                    cont.invokeOnCancellation {
                        workerLoop.removeInterest(selectionKey, SelectionKey.OP_CONNECT)
                        selectionKey.cancel()
                        runCatching { socketChannel.close() }
                    }
                }
                socketChannel.finishConnect()
            } catch (e: Exception) {
                selectionKey.cancel()
                runCatching { socketChannel.close() }
                throw e
            }
        }

        val remoteAddr = NioPipelinedChannel.toSocketAddress(socketChannel.remoteAddress)
        val localAddr = NioPipelinedChannel.toSocketAddress(socketChannel.localAddress)

        logger.debug { "Connected to $remoteAddr" }
        val transport = NioIoTransport(socketChannel, selectionKey, workerLoop, allocator)
        return NioPipelinedChannel(transport, logger, remoteAddr, localAddr)
    }

    /**
     * Binds a pipeline-based server on [host]:[port].
     *
     * Creates a callback-driven server that processes connections entirely
     * through [Pipeline] handlers — no coroutine suspension on the hot path.
     *
     * Unlike Native engines, NIO requires `channel.register()` on the
     * EventLoop thread (Selector blocks during select). The boss loop
     * registers the ServerSocketChannel, and worker loops register accepted
     * client channels via [NioEventLoop.dispatch].
     *
     * Non-suspend: uses [NioEventLoop.registerChannelBlocking] to register
     * the ServerSocketChannel synchronously (Pipeline zero-coroutine principle).
     *
     * @param pipelineInitializer Callback to configure the pipeline for each connection.
     * @return A [PipelinedServer] for lifecycle management.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer = when (address) {
        is InetSocketAddress -> bindPipelineInet(address, config, pipelineInitializer)
        is UnixSocketAddress -> bindPipelineUnix(address, config, pipelineInitializer)
    }

    private fun bindPipelineInet(
        address: InetSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val host = address.requireIpLiteral()
        val port = address.port
        val serverChannel = java.nio.channels.ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(JavaInetSocketAddress(host, port), config.backlog)

        val selectionKey = bossLoop.registerChannelBlocking(serverChannel)

        val localAddr = NioPipelinedChannel.toSocketAddress(serverChannel.localAddress)
        logger.debug { "Pipeline bound to $localAddr" }

        val serverPipeline = NioPipelinedServerChannel(
            serverChannel = serverChannel,
            selectionKey = selectionKey,
            bossLoop = bossLoop,
            workerGroup = workerGroup,
            localAddr = localAddr ?: error("Failed to get local address"),
            logger = logger,
            config = config,
            pipelineInitializer = pipelineInitializer,
        )
        serverPipeline.start()
        return serverPipeline
    }

    private fun bindPipelineUnix(
        address: UnixSocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly("NioEngine does not support abstract-namespace Unix sockets (JVM UnixDomainSocketAddress is filesystem-only)")

        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        serverChannel.configureBlocking(false)
        serverChannel.bind(UnixDomainSocketAddress.of(Path.of(address.path)), config.backlog)

        val selectionKey = bossLoop.registerChannelBlocking(serverChannel)

        val localAddr = NioPipelinedChannel.toSocketAddress(serverChannel.localAddress) ?: address
        logger.debug { "Pipeline bound to $localAddr" }

        val serverPipeline = NioPipelinedServerChannel(
            serverChannel = serverChannel,
            selectionKey = selectionKey,
            bossLoop = bossLoop,
            workerGroup = workerGroup,
            localAddr = localAddr,
            logger = logger,
            config = config,
            pipelineInitializer = pipelineInitializer,
        )
        serverPipeline.start()
        return serverPipeline
    }

    /**
     * Closes the engine: cancels every child coroutine launched on this
     * engine's scope, joins their completion, then stops the boss and
     * worker EventLoops.
     *
     * The `job.cancelAndJoin()` step runs first so that children
     * suspended on engine dispatchers observe cancellation while their
     * dispatcher is still alive (otherwise the cancellation resume
     * would be dispatched to a dead dispatcher and never fire). Only
     * after every child has unwound are the dispatcher threads torn
     * down. Idempotent.
     */
    override suspend fun close() {
        if (!closed) {
            closed = true
            coroutineContext.job.cancelAndJoin()
            bossLoop.close()
            workerGroup.close()
            logger.debug { "Engine closed" }
        }
    }

    companion object {
        /** Resolves threads=0 to available CPU cores. */
        private fun resolveThreads(config: IoEngineConfig): Int =
            if (config.threads > 0) {
                config.threads
            } else {
                Runtime.getRuntime().availableProcessors()
            }
    }
}
