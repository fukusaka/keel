package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.PipelinedServer
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.github.fukusaka.keel.core.Channel as KeelChannel
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Netty-based [StreamEngine] implementation for JVM.
 *
 * Uses Netty's [ServerBootstrap] for server-side and [Bootstrap] for
 * client-side TCP connections. Netty manages its own EventLoop threads
 * (boss group for accept, worker group for I/O).
 *
 * **Coroutine integration**: All suspend functions use
 * [suspendCancellableCoroutine] with Netty's [ChannelFuture] listeners
 * for non-blocking operation. No thread blocking occurs.
 *
 * **auto-read=false**: Each accepted/connected channel starts with
 * `autoRead` disabled. Auto-read is enabled when [NettyPipelinedChannel.ensureBridge]
 * is called (Channel mode) or [NettyPipelinedChannel.armRead] is called
 * (Pipeline mode), enabling push-model semantics via Netty's channelRead
 * callbacks.
 *
 * ```
 * NettyEngine (owns NioEventLoopGroups)
 *   |
 *   +-- bind() ---------> NettyServer (Channel mode: accept → suspend I/O)
 *   |                       |
 *   |                       +-- accept() --> NettyPipelinedChannel
 *   |
 *   +-- bindPipeline() --> NettyPipelinedServer (Pipeline mode: push I/O)
 *   |
 *   +-- connect() -------> NettyPipelinedChannel
 * ```
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] is passed
 *               directly to Netty's `NioEventLoopGroup`. 0 (default) lets Netty
 *               choose automatically (`cpu * 2`).
 */
class NettyEngine(
    override val config: IoEngineConfig = IoEngineConfig(),
) : StreamEngine {

    private val logger = config.loggerFactory.logger("NettyEngine")
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup(config.threads)
    private var closed = false

    override suspend fun bind(host: String, port: Int, bindConfig: BindConfig): ServerChannel {
        check(!closed) { "Engine is closed" }

        // Two-phase init: create NettyServer before bind so the
        // ChannelInitializer closure can call onNewChannel(). The underlying
        // Netty server channel and local address are set via init() after
        // the bind future completes.
        val serverChannel = NettyServer.create()

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.SO_BACKLOG, bindConfig.backlog)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // Disable auto-read initially. Auto-read is enabled
                    // when ensureBridge() or armRead() is called.
                    ch.config().isAutoRead = false
                    val remoteAddr = NettyPipelinedChannel.toSocketAddress(ch.remoteAddress())
                    val localAddr = NettyPipelinedChannel.toSocketAddress(ch.localAddress())
                    val keelChannel = NettyPipelinedChannel(
                        ch, config.allocator, remoteAddr, localAddr, logger,
                    )
                    ch.pipeline().addLast(keelChannel.handler)
                    serverChannel.onNewChannel(keelChannel)
                }
            })

        val nettyServerCh = suspendCancellableCoroutine<NettyNativeChannel> { cont ->
            bootstrap.bind(InetSocketAddress(host, port)).addListener { f ->
                val cf = f as ChannelFuture
                if (cf.isSuccess) {
                    cont.resume(cf.channel())
                } else {
                    cont.resumeWithException(
                        cf.cause() ?: Exception("bind failed"),
                    )
                }
            }
        }

        val localAddr = NettyPipelinedChannel.toSocketAddress(nettyServerCh.localAddress())
            ?: error("Failed to get local address")

        serverChannel.init(nettyServerCh, localAddr)
        logger.debug { "Bound to ${localAddr.host}:${localAddr.port}" }
        return serverChannel
    }

    /**
     * Connects to a remote server via Netty [Bootstrap].
     *
     * Unlike [bind], the handler is added **after** connect completes
     * because there is no ChannelInitializer race — the channel is not
     * yet receiving data until [NettyPipelinedChannel.armRead] is called
     * (`autoRead = false`).
     */
    override suspend fun connect(host: String, port: Int): KeelChannel {
        check(!closed) { "Engine is closed" }

        val bootstrap = Bootstrap()
            .group(workerGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // Disable auto-read initially
                    ch.config().isAutoRead = false
                }
            })

        val nettyChannel = suspendCancellableCoroutine<NettyNativeChannel> { cont ->
            bootstrap.connect(InetSocketAddress(host, port)).addListener { f ->
                val cf = f as ChannelFuture
                if (cf.isSuccess) {
                    cont.resume(cf.channel())
                } else {
                    cont.resumeWithException(
                        cf.cause() ?: Exception("connect failed"),
                    )
                }
            }
        }

        val remoteAddr = NettyPipelinedChannel.toSocketAddress(nettyChannel.remoteAddress())
        val localAddr = NettyPipelinedChannel.toSocketAddress(nettyChannel.localAddress())

        val keelChannel = NettyPipelinedChannel(
            nettyChannel, config.allocator, remoteAddr, localAddr, logger,
        )
        nettyChannel.pipeline().addLast(keelChannel.handler)

        logger.debug { "Connected to ${remoteAddr?.host}:${remoteAddr?.port}" }
        return keelChannel
    }

    /**
     * Binds a server socket with Pipeline-mode connection handling.
     *
     * Each accepted connection creates a [NettyPipelinedChannel], invokes
     * the [pipelineInitializer] callback to install handlers, and immediately
     * calls [NettyPipelinedChannel.armRead] to enable push-model I/O.
     *
     * Non-suspend: uses Netty's `bind().sync()` to block until the server
     * socket is ready. This is acceptable because `bindPipeline` is called
     * once at startup, not on the hot path.
     */
    override fun bindPipeline(
        host: String,
        port: Int,
        config: BindConfig,
        pipelineInitializer: (PipelinedChannel) -> Unit,
    ): PipelinedServer {
        check(!closed) { "Engine is closed" }

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .option(ChannelOption.SO_BACKLOG, config.backlog)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    ch.config().isAutoRead = false
                    val remoteAddr = NettyPipelinedChannel.toSocketAddress(ch.remoteAddress())
                    val localAddr = NettyPipelinedChannel.toSocketAddress(ch.localAddress())
                    val keelChannel = NettyPipelinedChannel(
                        ch, this@NettyEngine.config.allocator, remoteAddr, localAddr, logger,
                    )
                    ch.pipeline().addLast(keelChannel.handler)
                    config.initializeConnection(keelChannel)
                    pipelineInitializer(keelChannel)
                    keelChannel.armRead()
                }
            })

        val nettyServerCh = bootstrap.bind(host, port).sync().channel()
        val localAddr = NettyPipelinedChannel.toSocketAddress(nettyServerCh.localAddress())
            ?: error("Failed to get local address")

        logger.debug { "Pipeline bound to ${localAddr.host}:${localAddr.port}" }
        return NettyPipelinedServer(nettyServerCh, localAddr)
    }

    override fun close() {
        if (!closed) {
            closed = true
            // Short quiet period (0) and timeout (2s) to avoid hanging on shutdown.
            // Default shutdownGracefully() uses 2s quiet + 15s timeout which
            // causes CI timeouts when channels are not fully drained.
            workerGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS).sync()
            bossGroup.shutdownGracefully(0, 2, java.util.concurrent.TimeUnit.SECONDS).sync()
            logger.debug { "Engine closed" }
        }
    }

    /**
     * [PipelinedServer] backed by a Netty server channel.
     *
     * Wraps the underlying Netty channel for lifecycle management.
     * [close] blocks until the Netty channel is fully closed to ensure
     * the listen socket is released.
     */
    private class NettyPipelinedServer(
        private val serverChannel: NettyNativeChannel,
        override val localAddress: SocketAddress,
    ) : PipelinedServer {
        @Volatile
        private var closed = false

        override val isActive: Boolean get() = !closed && serverChannel.isActive

        override fun close() {
            if (!closed) {
                closed = true
                serverChannel.close().sync()
            }
        }
    }
}
