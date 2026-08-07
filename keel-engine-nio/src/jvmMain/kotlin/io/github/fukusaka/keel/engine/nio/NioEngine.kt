package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.BindSpec
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.ConnectConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.github.fukusaka.keel.core.bindAllOrRollback
import io.github.fukusaka.keel.core.connectWithFallback
import io.github.fukusaka.keel.core.requireFilesystemOnly
import io.github.fukusaka.keel.core.requireIpLiteral
import io.github.fukusaka.keel.core.resolveFirst
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.guarded
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
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
 *   |     +-- bind() → NioStreamServer (cached SelectionKey)
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
 *               to `availableProcessors()`. Bench A/B (k6 sse 50 VU / 15s)
 *               showed `availableProcessors() * 2` (Netty's
 *               `NioEventLoopGroup` default) regresses throughput by 9-18 %
 *               on both macOS M1 and a 32-core Ryzen Linux host, because each keel
 *               EventLoop already saturates its core under per-frame-flush
 *               SSE and extra workers buy more cross-thread coordination
 *               than parallelism (worker-thread-count candidate (c) — rejected).
 */
class NioEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
) : StreamEngine {

    override val coroutineContext: CoroutineContext = SupervisorJob()

    /**
     * The configured factory, wrapped so no log statement in this engine can
     * throw. Read once here rather than at each use; see `guarded`.
     */
    private val guardedLoggerFactory = config.loggerFactory.guarded()

    private val logger = guardedLoggerFactory.logger("NioEngine")
    private val eventLoopLogger = guardedLoggerFactory.logger("NioEventLoop")
    private val bossLoop = NioEventLoop("keel-nio-boss", eventLoopLogger)
    private val workerGroup =
        NioEventLoopGroup(
            resolveThreads(config),
            "keel-nio-worker",
            eventLoopLogger,
            config.allocator,
            config.readBufferSize,
            config.idleTimeoutMillis,
            config.flushCoalescing,
        )
    private var closed = false

    /**
     * Binds a suspend-based server on [host]:[port].
     *
     * Opens a [ServerSocketChannel] in non-blocking mode, registers it with
     * the boss EventLoop's Selector, and returns a [NioStreamServer] whose
     * [accept][NioStreamServer.accept] returns [NioPipelinedChannel] instances.
     *
     * @throws IllegalStateException if the engine is closed.
     */
    override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): StreamServer = when (address) {
        is InetSocketAddress -> bindInet(address, bindConfig)
        is UnixSocketAddress -> bindUnix(address, bindConfig)
    }

    private suspend fun bindUnix(address: UnixSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly(
            "NioEngine does not support abstract-namespace Unix sockets (JVM UnixDomainSocketAddress is filesystem-only)",
        )

        val serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            serverChannel.configureBlocking(false)
            serverChannel.bind(UnixDomainSocketAddress.of(Path.of(address.path)), bindConfig.backlog)

            val localAddr = NioPipelinedChannel.toSocketAddress(serverChannel.localAddress) ?: address
            val selectionKey = bossLoop.registerChannel(serverChannel)

            logger.debug { "Bound to $localAddr" }
            return NioStreamServer(
                serverChannel,
                selectionKey,
                bossLoop,
                workerGroup,
                localAddr,
                bindConfig,
                config.idleReadPolicy,
                logger,
            )
        } catch (t: Throwable) {
            closeQuietly(serverChannel, "bindUnix cleanup")
            throw t
        }
    }

    private suspend fun bindInet(address: InetSocketAddress, bindConfig: BindConfig): StreamServer {
        check(!closed) { "Engine is closed" }

        val host = address.resolveFirst(config.resolver).toCanonicalString()
        val port = address.port
        val serverChannel = ServerSocketChannel.open()
        try {
            serverChannel.configureBlocking(false)
            serverChannel.bind(JavaInetSocketAddress(host, port), bindConfig.backlog)

            val localAddr = NioPipelinedChannel.toSocketAddress(serverChannel.localAddress)
                ?: error("Failed to get local address")

            // One-time registration with the boss Selector
            val selectionKey = bossLoop.registerChannel(serverChannel)

            logger.debug { "Bound to $localAddr" }
            return NioStreamServer(
                serverChannel,
                selectionKey,
                bossLoop,
                workerGroup,
                localAddr,
                bindConfig,
                config.idleReadPolicy,
                logger,
            )
        } catch (t: Throwable) {
            closeQuietly(serverChannel, "bindInet cleanup")
            throw t
        }
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
    override suspend fun connect(address: SocketAddress): Channel = connect(address, ConnectConfig.DEFAULT)

    override suspend fun connect(address: SocketAddress, config: ConnectConfig): Channel = when (address) {
        is InetSocketAddress ->
            connectInet(address, config.socketOptions, config.readBufferSize, config.idleTimeoutMillis)
        is UnixSocketAddress ->
            connectUnix(address, config.socketOptions, config.readBufferSize, config.idleTimeoutMillis)
    }

    private suspend fun connectUnix(
        address: UnixSocketAddress,
        socketOptions: SocketOptions,
        readBufferSizeOverride: Int?,
        idleTimeoutOverride: Long?,
    ): Channel {
        check(!closed) { "Engine is closed" }
        address.requireFilesystemOnly(
            "NioEngine does not support abstract-namespace Unix sockets (JVM UnixDomainSocketAddress is filesystem-only)",
        )

        val socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX)
        socketChannel.configureBlocking(false)
        applySocketOptions(socketChannel, socketOptions)
        val workerLoop = workerGroup.next()

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
        val transport = NioIoTransport(
            socketChannel,
            selectionKey,
            workerLoop,
            workerLoop.allocator,
            config.idleReadPolicy,
            readBufferSizeOverride ?: workerLoop.readBufferSize,
            idleTimeoutOverride ?: workerLoop.idleTimeoutMillis,
        )
        return NioPipelinedChannel(transport, logger, remoteAddr, localAddr)
    }

    private suspend fun connectInet(
        address: InetSocketAddress,
        socketOptions: SocketOptions,
        readBufferSizeOverride: Int?,
        idleTimeoutOverride: Long?,
    ): Channel {
        check(!closed) { "Engine is closed" }
        return address.connectWithFallback(config.resolver) { ip ->
            connectToIp(
                ip.toCanonicalString(),
                address.port,
                socketOptions,
                readBufferSizeOverride,
                idleTimeoutOverride,
            )
        }
    }

    private suspend fun connectToIp(
        host: String,
        port: Int,
        socketOptions: SocketOptions,
        readBufferSizeOverride: Int?,
        idleTimeoutOverride: Long?,
    ): Channel {
        val socketChannel = SocketChannel.open()
        socketChannel.configureBlocking(false)
        applySocketOptions(socketChannel, socketOptions)
        val workerLoop = workerGroup.next()

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
            // NioEventLoop.processSelectedKeys — see NioStreamServer KDoc for the
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
        val transport = NioIoTransport(
            socketChannel,
            selectionKey,
            workerLoop,
            workerLoop.allocator,
            config.idleReadPolicy,
            readBufferSizeOverride ?: workerLoop.readBufferSize,
            idleTimeoutOverride ?: workerLoop.idleTimeoutMillis,
        )
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
     * @return A [PipelinedStreamServer] for lifecycle management.
     */
    override fun bindPipeline(
        address: SocketAddress,
        config: BindConfig,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedStreamServer = bindPipeline(listOf(BindSpec(address, config)), pipelineInitializer)

    /**
     * Multi-address pipeline bind: every entry of [binds] becomes one
     * listener of a single [NioPipelinedStreamServer], all armed on the
     * shared boss loop. All-or-nothing: a failing bind closes the
     * listeners bound so far (waking the boss loop so their ports free
     * promptly) and rethrows.
     */
    override fun bindPipeline(
        binds: List<BindSpec>,
        pipelineInitializer: (io.github.fukusaka.keel.pipeline.PipelinedChannel) -> Unit,
    ): PipelinedStreamServer {
        check(!closed) { "Engine is closed" }
        val listeners = bindAllOrRollback(
            binds = binds,
            logger = logger,
            closeOne = { listener: NioPipelinedStreamServer.Listener ->
                listener.serverChannel.close()
                // Same deferred-close mechanics as a regular listener close:
                // wake the boss loop so the rolled-back port frees promptly.
                bossLoop.wakeup()
            },
        ) { spec -> openPipelineListener(spec) }
        val serverPipeline = NioPipelinedStreamServer(
            listeners = listeners,
            bossLoop = bossLoop,
            workerGroup = workerGroup,
            logger = logger,
            idleReadPolicy = this@NioEngine.config.idleReadPolicy,
            pipelineInitializer = pipelineInitializer,
        )
        try {
            serverPipeline.start()
        } catch (t: Throwable) {
            serverPipeline.close()
            throw t
        }
        return serverPipeline
    }

    /**
     * Opens, binds, and boss-registers one pipeline listen socket.
     * Cleans up its own channel on failure so [bindAllOrRollback] only has
     * to roll back the listeners that were fully opened before it.
     */
    private fun openPipelineListener(spec: BindSpec): NioPipelinedStreamServer.Listener {
        val address = spec.address
        val serverChannel = when (address) {
            is InetSocketAddress -> java.nio.channels.ServerSocketChannel.open()
            is UnixSocketAddress -> {
                address.requireFilesystemOnly(
                    "NioEngine does not support abstract-namespace Unix sockets " +
                        "(JVM UnixDomainSocketAddress is filesystem-only)",
                )
                ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            }
        }
        try {
            serverChannel.configureBlocking(false)
            when (address) {
                is InetSocketAddress -> serverChannel.bind(
                    JavaInetSocketAddress(address.requireIpLiteral(), address.port),
                    spec.config.backlog,
                )
                is UnixSocketAddress -> serverChannel.bind(
                    UnixDomainSocketAddress.of(Path.of(address.path)),
                    spec.config.backlog,
                )
            }

            val selectionKey = bossLoop.registerChannelBlocking(serverChannel)

            val localAddr = NioPipelinedChannel.toSocketAddress(serverChannel.localAddress) ?: address
            logger.debug { "Pipeline bound to $localAddr" }

            return NioPipelinedStreamServer.Listener(
                serverChannel = serverChannel,
                selectionKey = selectionKey,
                localAddress = localAddr,
                config = spec.config,
            )
        } catch (t: Throwable) {
            closeQuietly(serverChannel, "bindPipeline listener cleanup")
            throw t
        }
    }

    /**
     * Closes [channel] during an error cleanup path, logging any
     * secondary [java.io.IOException] from the close itself rather than
     * re-throwing it — the original failure that triggered the cleanup
     * is preserved by the caller's `throw t`.
     */
    private fun closeQuietly(channel: java.nio.channels.ServerSocketChannel, context: String) {
        try {
            channel.close()
        } catch (e: Throwable) {
            logger.warn(e) { "ServerSocketChannel.close() failed during $context" }
        }
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
