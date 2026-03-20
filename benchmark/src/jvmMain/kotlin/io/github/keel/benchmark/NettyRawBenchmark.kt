package io.github.keel.benchmark

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*

/**
 * Raw Netty HTTP server for benchmarking.
 *
 * No framework (Ktor/Spring/Vert.x) overhead — pure Netty ServerBootstrap
 * with a minimal ChannelHandler that writes HTTP responses directly.
 * Represents the theoretical maximum for Netty-based I/O.
 */
private val nettyRawLargePayload = Unpooled.unreleasableBuffer(
    Unpooled.wrappedBuffer("x".repeat(102_400).toByteArray())
)

fun startNettyRaw(config: BenchmarkConfig) {
    val s = config.socket
    val bossGroup = NioEventLoopGroup(1)
    val workerGroup = NioEventLoopGroup(s.threads ?: 0) // 0 = Netty default (cpu * 2)

    val bootstrap = ServerBootstrap()
        .group(bossGroup, workerGroup)
        .channel(NioServerSocketChannel::class.java)
        .childHandler(object : ChannelInitializer<SocketChannel>() {
            override fun initChannel(ch: SocketChannel) {
                ch.pipeline().addLast(
                    HttpServerCodec(),
                    HttpObjectAggregator(65536),
                    BenchmarkHandler(config.connectionClose),
                )
            }
        })

    // Apply socket options
    s.tcpNoDelay?.let { bootstrap.childOption(ChannelOption.TCP_NODELAY, it) }
    s.backlog?.let { bootstrap.option(ChannelOption.SO_BACKLOG, it) }
    s.sendBuffer?.let { bootstrap.childOption(ChannelOption.SO_SNDBUF, it) }
    s.receiveBuffer?.let { bootstrap.childOption(ChannelOption.SO_RCVBUF, it) }
    s.reuseAddress?.let { bootstrap.option(ChannelOption.SO_REUSEADDR, it) }

    val channel = bootstrap.bind(config.port).sync().channel()
    println("Netty raw server started on port ${config.port}")
    channel.closeFuture().sync()

    bossGroup.shutdownGracefully()
    workerGroup.shutdownGracefully()
}

/**
 * Minimal HTTP handler that responds to /hello and /large.
 */
private class BenchmarkHandler(
    private val connectionClose: Boolean,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val (content, contentType) = when (request.uri()) {
            "/hello" -> Unpooled.copiedBuffer("Hello, World!", Charsets.UTF_8) to "text/plain"
            "/large" -> nettyRawLargePayload.retainedDuplicate() to "text/plain"
            else -> {
                val response = DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.NOT_FOUND,
                    Unpooled.copiedBuffer("Not Found", Charsets.UTF_8),
                )
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
                if (connectionClose) response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                ctx.writeAndFlush(response)
                if (connectionClose) ctx.close()
                return
            }
        }

        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
        if (connectionClose) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }

        val future = ctx.writeAndFlush(response)
        if (connectionClose) {
            future.addListener(ChannelFutureListener.CLOSE)
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }
}
