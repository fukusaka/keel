package io.github.keel.engine.netty

import io.github.keel.core.IoEngine
import io.github.keel.core.IoEngineConfig
import io.github.keel.core.ServerChannel
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.ChannelInitializer
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import io.github.keel.core.Channel as KeelChannel

/**
 * Netty-based [IoEngine] implementation for JVM.
 *
 * Uses Netty's [ServerBootstrap] for server-side and [Bootstrap] for
 * client-side TCP connections. Netty manages its own EventLoop threads
 * (boss group for accept, worker group for I/O).
 *
 * **Push-to-pull bridge**: Netty's event-driven model (channelRead callback)
 * is bridged to keel's pull model (suspend read) via [LinkedBlockingQueue]
 * in [NettyChannel]. See [NettyChannel] KDoc for details.
 *
 * Phase (a): blocking I/O. All suspend functions block internally.
 * Netty's EventLoop runs in background threads.
 *
 * ```
 * NettyEngine (owns NioEventLoopGroups)
 *   |
 *   +-- bind() --> NettyServerChannel (wraps Netty ServerChannel)
 *   |                |
 *   |                +-- accept() --> NettyChannel (wraps Netty SocketChannel)
 *   |
 *   +-- connect() --> NettyChannel (wraps Netty SocketChannel)
 * ```
 *
 * @param config Engine-wide configuration (allocator, threads).
 */
class NettyEngine(
    private val config: IoEngineConfig = IoEngineConfig(),
) : IoEngine {

    private val bossGroup = NioEventLoopGroup(1)
    private val workerGroup = NioEventLoopGroup(config.threads)
    private var closed = false

    override suspend fun bind(host: String, port: Int): ServerChannel {
        check(!closed) { "Engine is closed" }

        // Accept queue shared between ChannelInitializer and NettyServerChannel.
        // Created upfront so the closure can capture it before bind completes.
        val acceptQueue = LinkedBlockingQueue<io.netty.channel.Channel>()

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    acceptQueue.put(ch)
                }
            })

        val nettyServerCh = bootstrap.bind(InetSocketAddress(host, port)).sync().channel()

        val localAddr = NettyChannel.toSocketAddress(nettyServerCh.localAddress())
            ?: error("Failed to get local address")

        // Pass the shared acceptQueue to the ServerChannel
        return NettyServerChannel(nettyServerCh, localAddr, config.allocator, acceptQueue)
    }

    /**
     * Phase (a): blocking connect via Netty [Bootstrap].
     */
    override suspend fun connect(host: String, port: Int): KeelChannel {
        check(!closed) { "Engine is closed" }

        val bootstrap = Bootstrap()
            .group(workerGroup)
            .channel(NioSocketChannel::class.java)
            .handler(object : ChannelInitializer<SocketChannel>() {
                override fun initChannel(ch: SocketChannel) {
                    // Handler will be added after connect completes
                }
            })

        val nettyChannel = bootstrap.connect(InetSocketAddress(host, port)).sync().channel()

        val remoteAddr = NettyChannel.toSocketAddress(nettyChannel.remoteAddress())
        val localAddr = NettyChannel.toSocketAddress(nettyChannel.localAddress())

        val keelChannel = NettyChannel(nettyChannel, config.allocator, remoteAddr, localAddr)
        nettyChannel.pipeline().addLast(keelChannel.handler)

        return keelChannel
    }

    override fun close() {
        if (!closed) {
            closed = true
            workerGroup.shutdownGracefully().sync()
            bossGroup.shutdownGracefully().sync()
        }
    }
}
