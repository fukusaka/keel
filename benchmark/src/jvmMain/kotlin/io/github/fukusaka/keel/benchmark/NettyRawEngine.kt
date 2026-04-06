package io.github.fukusaka.keel.benchmark

import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInitializer
import io.netty.channel.ChannelOption
import io.netty.channel.SimpleChannelInboundHandler
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpObjectAggregator
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpServerCodec
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.ssl.SslContextBuilder

/**
 * Raw Netty HTTP server for benchmarking.
 *
 * No framework (Ktor/Spring/Vert.x) overhead — pure Netty ServerBootstrap
 * with a minimal ChannelHandler that writes HTTP responses directly.
 * Represents the theoretical maximum for Netty-based I/O.
 */

/** Raw Netty ServerBootstrap engine settings. */
data class NettyRawEngineConfig(
    val maxContentLength: Int? = null,
) : EngineConfig {
    override fun displayTo(sb: StringBuilder, engine: String) {
        sb.appendLine("--- Engine-Specific (netty-raw) ---")
        sb.fmtLine("max-content-length:", maxContentLength?.let { "$it bytes" }
            ?: "$DEFAULT_MAX_CONTENT_LENGTH bytes (default)")
    }

    override fun toString(): String = maxContentLength?.let { "maxContentLength=$it" } ?: ""
}

private const val DEFAULT_MAX_CONTENT_LENGTH = 65536

private val nettyRawHelloPayload = Unpooled.unreleasableBuffer(
    Unpooled.wrappedBuffer("Hello, World!".toByteArray())
)
private val nettyRawLargePayload = Unpooled.unreleasableBuffer(
    Unpooled.wrappedBuffer("x".repeat(LARGE_PAYLOAD_SIZE).toByteArray())
)

object NettyRawEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val nr = config.engineConfig as? NettyRawEngineConfig ?: NettyRawEngineConfig()
        val s = config.socket
        val bossGroup = NioEventLoopGroup(1)
        val workerGroup = NioEventLoopGroup(s.threads ?: 0) // 0 = Netty default (cpu * 2)
        val maxContent = nr.maxContentLength ?: DEFAULT_MAX_CONTENT_LENGTH

        val bootstrap = ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel::class.java)
            .childHandler(object : ChannelInitializer<SocketChannel>() {
                private val sslCtx = if (config.tls != null) {
                    val ks = buildBenchmarkKeyStore()
                    val kmf = javax.net.ssl.KeyManagerFactory.getInstance(
                        javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm(),
                    )
                    kmf.init(ks, BENCHMARK_KEY_PASSWORD)
                    SslContextBuilder.forServer(kmf).build()
                } else {
                    null
                }

                override fun initChannel(ch: SocketChannel) {
                    sslCtx?.let { ch.pipeline().addLast(it.newHandler(ch.alloc())) }
                    ch.pipeline().addLast(
                        HttpServerCodec(),
                        HttpObjectAggregator(maxContent),
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
        return {
            channel.close().sync()
            bossGroup.shutdownGracefully()
            workerGroup.shutdownGracefully()
        }
    }

    // netty-raw: Netty default cpu*2 is already optimal for EventLoop model
    override fun tunedSocket(s: SocketConfig, cpuCores: Int): SocketConfig = s.copy(
        tcpNoDelay = s.tcpNoDelay ?: true,
        backlog = s.backlog ?: TUNED_BACKLOG,
        reuseAddress = s.reuseAddress ?: true,
    )

    override fun mergeConfig(base: EngineConfig, args: Map<String, String>): EngineConfig {
        val b = base as? NettyRawEngineConfig ?: NettyRawEngineConfig()
        return NettyRawEngineConfig(
            maxContentLength = args["max-content-length"]?.toInt() ?: b.maxContentLength,
        )
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        val cpuCores = availableProcessors()
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "${os.tcpNoDelay} (default by OS, via Netty)",
            reuseAddress = "${os.reuseAddress} (default by OS, via Netty)",
            backlog = "${os.backlog} (default by OS, estimated)",
            sendBuffer = "${os.sendBuffer} bytes (default by OS)",
            receiveBuffer = "${os.receiveBuffer} bytes (default by OS)",
            threads = "${cpuCores * 2} (default by Netty, cpu*2)",
        )
    }
}

/**
 * Minimal HTTP handler that responds to /hello and /large.
 */
private class BenchmarkHandler(
    private val connectionClose: Boolean,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val (content, contentType) = when (request.uri()) {
            "/hello" -> nettyRawHelloPayload.retainedDuplicate() to "text/plain"
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
