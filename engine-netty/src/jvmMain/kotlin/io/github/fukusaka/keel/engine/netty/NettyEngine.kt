package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.ServerChannel
import io.github.fukusaka.keel.logging.debug
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelInitializer
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
 * Netty-based [IoEngine] implementation for JVM.
 *
 * Uses Netty's [ServerBootstrap] for server-side and [Bootstrap] for
 * client-side TCP connections. Netty manages its own EventLoop threads
 * (boss group for accept, worker group for I/O).
 *
 * **Coroutine integration**: All suspend functions use
 * [suspendCancellableCoroutine] with Netty's [ChannelFuture] listeners
 * for non-blocking operation. No thread blocking occurs.
 *
 * **auto-read=false**: Each accepted/connected channel has `autoRead`
 * disabled. Data is only read when keel's [NettyChannel.read] explicitly
 * calls [NettyNativeChannel.read], enabling pull-model semantics and
 * natural TCP backpressure.
 *
 * ```
 * NettyEngine (owns NioEventLoopGroups)
 *   |
 *   +-- bind() --> NettyServer (wraps Netty ServerChannel)
 *   |                |
 *   |                +-- accept() --> NettyChannel (wraps Netty SocketChannel)
 *   |
 *   +-- connect() --> NettyChannel (wraps Netty SocketChannel)
 * ```
 *
 * @param config Engine-wide configuration. [IoEngineConfig.threads] is passed
 *               directly to Netty's `NioEventLoopGroup`. 0 (default) lets Netty
 *               choose automatically (`cpu * 2`).
 */
class NettyEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private val logger = config.loggerFactory.logger("NettyEngine")
    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup(config.threads)
    private var closed = false

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        // Two-phase init: create NettyServer before bind so the
        // ChannelInitializer closure can call onNewChannel(). The underlying
        // Netty server channel and local address are set via init() after
        // the bind future completes.
        val serverChannel = NettyServer.create()

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // Disable auto-read for pull-model semantics.
                    // Data is only read when keel calls nettyChannel.read().
                    ch.config().isAutoRead = false
                    val remoteAddr = NettyChannel.toSocketAddress(ch.remoteAddress())
                    val localAddr = NettyChannel.toSocketAddress(ch.localAddress())
                    val keelChannel = NettyChannel(ch, config.allocator, remoteAddr, localAddr)
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
                        cf.cause() ?: Exception("bind failed")
                    )
                }
            }
        }

        val localAddr = NettyChannel.toSocketAddress(nettyServerCh.localAddress())
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
     * yet receiving data until [NettyChannel.read] is called
     * (`autoRead = false`).
     */
    override suspend fun connect(host: String, port: Int): KeelChannel {
        check(!closed) { "Engine is closed" }

        val bootstrap = Bootstrap()
            .group(workerGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // Disable auto-read for pull-model semantics
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
                        cf.cause() ?: Exception("connect failed")
                    )
                }
            }
        }

        val remoteAddr = NettyChannel.toSocketAddress(nettyChannel.remoteAddress())
        val localAddr = NettyChannel.toSocketAddress(nettyChannel.localAddress())

        val keelChannel = NettyChannel(nettyChannel, config.allocator, remoteAddr, localAddr)
        nettyChannel.pipeline().addLast(keelChannel.handler)

        logger.debug { "Connected to ${remoteAddr?.host}:${remoteAddr?.port}" }
        return keelChannel
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
}
