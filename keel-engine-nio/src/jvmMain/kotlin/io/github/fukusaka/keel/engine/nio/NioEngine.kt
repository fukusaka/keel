package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.logging.debug
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

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
 * via [NioEventLoop.registerChannel]. Subsequent I/O uses [NioEventLoop.setInterest]
 * to toggle interest ops without JNI re-registration.
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
    override suspend fun bind(host: String, port: Int, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }

        val serverChannel = ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(InetSocketAddress(host, port), bindConfig.backlog)

        val localAddr = NioPipelinedChannel.toSocketAddress(serverChannel.localAddress)
            ?: error("Failed to get local address")

        // One-time registration with the boss Selector
        val selectionKey = bossLoop.registerChannel(serverChannel)

        logger.debug { "Bound to ${localAddr.host}:${localAddr.port}" }
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
    override suspend fun connect(host: String, port: Int): Channel {
        check(!closed) { "Engine is closed" }

        val socketChannel = SocketChannel.open()
        socketChannel.configureBlocking(false)
        val (workerLoop, allocator) = workerGroup.next()

        // Try connect first — loopback may succeed or fail immediately
        // without needing Selector registration.
        val connected = try {
            socketChannel.connect(InetSocketAddress(host, port))
        } catch (e: Exception) {
            socketChannel.close()
            throw e
        }

        // One-time registration with the worker Selector
        val selectionKey = workerLoop.registerChannel(socketChannel)

        if (!connected) {
            // Connection in progress — suspend until OP_CONNECT fires
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    workerLoop.setInterest(selectionKey, SelectionKey.OP_CONNECT, cont)
                    cont.invokeOnCancellation {
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

        logger.debug { "Connected to ${remoteAddr?.host}:${remoteAddr?.port}" }
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
        host: String,
        port: Int,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val serverChannel = java.nio.channels.ServerSocketChannel.open()
        serverChannel.configureBlocking(false)
        serverChannel.bind(java.net.InetSocketAddress(host, port), config.backlog)

        val selectionKey = bossLoop.registerChannelBlocking(serverChannel)

        val localAddr = NioPipelinedChannel.toSocketAddress(serverChannel.localAddress)
        logger.debug { "Pipeline bound to ${localAddr?.host}:${localAddr?.port}" }

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

    /**
     * Closes the engine, stopping both boss and worker EventLoops.
     *
     * Does NOT close existing channels — caller is responsible for closing
     * active connections before shutting down the engine. Idempotent.
     */
    override fun close() {
        if (!closed) {
            closed = true
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
