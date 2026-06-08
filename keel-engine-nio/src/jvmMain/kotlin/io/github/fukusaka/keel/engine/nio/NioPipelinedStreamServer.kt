package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

/**
 * Pipeline server channel for NIO-based connection acceptance on JVM.
 *
 * Uses the boss [NioEventLoop] to listen for incoming connections via
 * OP_ACCEPT on the ServerSocketChannel. Accepted connections are distributed
 * to worker EventLoops in round-robin.
 *
 * Channel registration with the worker's Selector is done via
 * [NioEventLoop.dispatch] + `channel.register()` on the worker thread,
 * because NIO Selector registration blocks if `select()` is in progress.
 */
internal class NioPipelinedStreamServer(
    private val serverChannel: ServerSocketChannel,
    private val selectionKey: SelectionKey,
    private val bossLoop: NioEventLoop,
    private val workerGroup: NioEventLoopGroup,
    private val localAddr: SocketAddress,
    private val logger: Logger,
    private val config: BindConfig,
    private val idleReadPolicy: IdleReadPolicy,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
) : PipelinedStreamServer {

    override val localAddress: SocketAddress get() = localAddr
    override val isActive: Boolean get() = !closed

    @Volatile
    private var closed = false
    private var workerIndex = 0 // Single boss thread only.

    /** Starts accepting connections on the boss EventLoop. */
    fun start() {
        armAccept()
    }

    private fun armAccept() {
        if (closed) return
        bossLoop.setInterestCallback(
            selectionKey,
            SelectionKey.OP_ACCEPT,
            Runnable {
                onAcceptable()
            },
        )
    }

    private fun onAcceptable() {
        if (closed) return
        while (true) {
            val client = serverChannel.accept() ?: break
            client.configureBlocking(false)
            applySocketOptions(client, config.childSocketOptions)
            dispatchToWorker(client)
        }
        armAccept()
    }

    private fun dispatchToWorker(client: java.nio.channels.SocketChannel) {
        val idx = workerIndex++ % workerGroup.size
        val workerLoop = workerGroup.at(idx)
        // Register on worker thread because NIO Selector.register() blocks during select().
        workerLoop.dispatch(
            kotlin.coroutines.EmptyCoroutineContext,
            Runnable {
                onWorkerAccept(client, workerLoop)
            },
        )
    }

    private fun onWorkerAccept(
        client: java.nio.channels.SocketChannel,
        loop: NioEventLoop,
    ) {
        // Register client with worker's Selector (must be on worker thread).
        val clientKey = client.register(loop.selector, 0)
        val transport = NioIoTransport(
            client,
            clientKey,
            loop,
            loop.allocator,
            idleReadPolicy,
            config.readBufferSize ?: loop.readBufferSize,
            config.idleTimeoutMillis ?: loop.idleTimeoutMillis,
        )
        val channel = NioPipelinedChannel(transport, logger)
        config.initializeConnection(channel)
        pipelineInitializer(channel)
        transport.readEnabled = true
    }

    /**
     * Stops accepting and closes the ServerSocketChannel.
     *
     * Pending accept callbacks become no-ops (closed flag check).
     * Does NOT close worker EventLoops or existing client channels —
     * caller (typically [NioEngine.close]) is responsible. Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        serverChannel.close()
    }
}
