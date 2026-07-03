package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

/**
 * Pipeline server channel for NIO-based connection acceptance on JVM.
 *
 * One server owns one or more [Listener]s (one per bound address — the
 * multi-address `bindPipeline` overload; a single-address bind is the
 * one-element case). Every listener is armed for OP_ACCEPT on the shared
 * boss [NioEventLoop]; accepted connections are distributed to worker
 * EventLoops in round-robin regardless of the listener they arrived on.
 *
 * Channel registration with the worker's Selector is done via
 * [NioEventLoop.dispatch] + `channel.register()` on the worker thread,
 * because NIO Selector registration blocks if `select()` is in progress.
 */
internal class NioPipelinedStreamServer(
    private val listeners: List<Listener>,
    private val bossLoop: NioEventLoop,
    private val workerGroup: NioEventLoopGroup,
    private val logger: Logger,
    private val idleReadPolicy: IdleReadPolicy,
    private val pipelineInitializer: (PipelinedChannel) -> Unit,
) : PipelinedStreamServer {

    init {
        require(listeners.isNotEmpty()) { "listeners must not be empty" }
    }

    override val localAddress: SocketAddress get() = listeners.first().localAddress
    override val localAddresses: List<SocketAddress> get() = listeners.map { it.localAddress }
    override val isActive: Boolean get() = !closed

    @Volatile
    private var closed = false
    private var workerIndex = 0 // Single boss thread only.

    /** Starts accepting connections on the boss EventLoop (every listener). */
    fun start() {
        listeners.forEach { armAccept(it) }
    }

    private fun armAccept(listener: Listener) {
        if (closed) return
        bossLoop.setInterestCallback(
            listener.selectionKey,
            SelectionKey.OP_ACCEPT,
            Runnable {
                onAcceptable(listener)
            },
        )
    }

    private fun onAcceptable(listener: Listener) {
        if (closed) return
        while (true) {
            val client = listener.serverChannel.accept() ?: break
            client.configureBlocking(false)
            applySocketOptions(client, listener.config.childSocketOptions)
            dispatchToWorker(client, listener)
        }
        armAccept(listener)
    }

    private fun dispatchToWorker(client: java.nio.channels.SocketChannel, listener: Listener) {
        val idx = workerIndex++ % workerGroup.size
        val workerLoop = workerGroup.at(idx)
        // Register on worker thread because NIO Selector.register() blocks during select().
        workerLoop.dispatch(
            kotlin.coroutines.EmptyCoroutineContext,
            Runnable {
                onWorkerAccept(client, workerLoop, listener)
            },
        )
    }

    private fun onWorkerAccept(
        client: java.nio.channels.SocketChannel,
        loop: NioEventLoop,
        listener: Listener,
    ) {
        // Register client with worker's Selector (must be on worker thread).
        val clientKey = client.register(loop.selector, 0)
        val transport = NioIoTransport(
            client,
            clientKey,
            loop,
            loop.allocator,
            idleReadPolicy,
            listener.config.readBufferSize ?: loop.readBufferSize,
            listener.config.idleTimeoutMillis ?: loop.idleTimeoutMillis,
        )
        // The accepted socket's own local endpoint: for a specific-address
        // listener it equals the listener address; for a wildcard bind it is
        // the concrete interface address with the listener's port. Lets the
        // shared pipeline initializer branch on the listening address. The
        // socket query can fail if the peer disconnected in the accept →
        // worker-dispatch window, so fall back to the listener address.
        val channelLocal = runCatching {
            NioPipelinedChannel.toSocketAddress(client.localAddress)
        }.getOrNull() ?: listener.localAddress
        val channel = NioPipelinedChannel(transport, logger, localAddress = channelLocal)
        listener.config.initializeConnection(channel)
        pipelineInitializer(channel)
        transport.readEnabled = true
    }

    /**
     * Stops accepting and closes every listener's ServerSocketChannel.
     *
     * A close failure on one listener is logged and does not stop the
     * remaining listeners from closing. Pending accept callbacks become
     * no-ops (closed flag check). Does NOT close worker EventLoops or
     * existing client channels — caller (typically [NioEngine.close]) is
     * responsible. Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        for (listener in listeners) {
            try {
                listener.serverChannel.close()
            } catch (t: Throwable) {
                logger.warn(t) { "closing the listener for ${listener.localAddress} failed" }
            }
        }
        // The kernel-level close (and the port release) is deferred until
        // the boss selector's next selection operation processes the
        // cancelled keys — wake an idle boss loop once so every closed
        // listener's port frees promptly instead of lingering until the
        // next event.
        bossLoop.wakeup()
    }

    /**
     * One bound listen socket of this server: its channel, its boss-loop
     * registration, the resolved bind address, and the per-address config
     * applied to connections accepted on it.
     */
    internal class Listener(
        val serverChannel: ServerSocketChannel,
        val selectionKey: SelectionKey,
        val localAddress: SocketAddress,
        val config: BindConfig,
    )
}
