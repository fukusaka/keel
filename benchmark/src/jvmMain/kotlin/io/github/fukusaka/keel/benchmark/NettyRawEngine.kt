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
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketFrame
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler
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
                        // WebSocketServerProtocolHandler intercepts the upgrade
                        // for /ws-echo, attaches the framing codec, and lets
                        // non-WS HTTP requests pass through to BenchmarkHandler.
                        WebSocketServerProtocolHandler("/ws-echo"),
                        WebSocketEchoHandler(),
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
 * Minimal HTTP handler that responds to:
 *   - GET /hello, GET /large (static payloads)
 *   - POST /upload-stream (drains request body, replies with byte count)
 *   - GET /sse-stream?count=N&size=M (chunked SSE-style response stream)
 */
private class BenchmarkHandler(
    private val connectionClose: Boolean,
) : SimpleChannelInboundHandler<FullHttpRequest>() {

    @Suppress("ReturnCount")
    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        // Path without query string for matching.
        val rawUri = request.uri()
        val pathEnd = rawUri.indexOf('?').takeIf { it >= 0 } ?: rawUri.length
        val path = rawUri.substring(0, pathEnd)

        when (path) {
            "/hello" -> respondStatic(ctx, nettyRawHelloPayload.retainedDuplicate(), "text/plain")
            "/large" -> respondStatic(ctx, nettyRawLargePayload.retainedDuplicate(), "text/plain")
            "/upload-stream" -> respondUploadAck(ctx, request)
            "/sse-stream" -> respondSseStream(ctx, rawUri.substring(pathEnd))
            else -> respondNotFound(ctx)
        }
    }

    private fun respondStatic(
        ctx: ChannelHandlerContext,
        content: io.netty.buffer.ByteBuf,
        contentType: String,
    ) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content)
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType)
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
        applyConnectionHeader(response)
        val future = ctx.writeAndFlush(response)
        if (connectionClose) future.addListener(ChannelFutureListener.CLOSE)
    }

    private fun respondUploadAck(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        // HttpObjectAggregator already accumulated the body — match the
        // aggregate-style accounting other engine handlers use.
        val received = request.content().readableBytes()
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            HttpResponseStatus.OK,
            Unpooled.wrappedBuffer(nettyRawUploadAckBytes),
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
        response.headers().set("X-Bytes-Received", received.toString())
        applyConnectionHeader(response)
        val future = ctx.writeAndFlush(response)
        if (connectionClose) future.addListener(ChannelFutureListener.CLOSE)
    }

    private fun respondSseStream(ctx: ChannelHandlerContext, query: String) {
        val count = parseQueryInt(query, "count") ?: SSE_DEFAULT_COUNT
        val size = parseQueryInt(query, "size") ?: SSE_DEFAULT_SIZE
        val frame = "data: ${"x".repeat(size)}\n\n".toByteArray()
        // Send response head with Transfer-Encoding: chunked, then write
        // raw frame buffers. Netty serializes each write as one HTTP
        // chunk because we send a HttpResponse without Content-Length.
        val head = io.netty.handler.codec.http.DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream")
        head.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        applyConnectionHeader(head)
        ctx.write(head)
        repeat(count) {
            ctx.write(io.netty.handler.codec.http.DefaultHttpContent(Unpooled.wrappedBuffer(frame)))
        }
        val future = ctx.writeAndFlush(io.netty.handler.codec.http.LastHttpContent.EMPTY_LAST_CONTENT)
        if (connectionClose) future.addListener(ChannelFutureListener.CLOSE)
    }

    private fun respondNotFound(ctx: ChannelHandlerContext) {
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
    }

    private fun applyConnectionHeader(response: io.netty.handler.codec.http.HttpResponse) {
        if (connectionClose) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
        } else {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
        }
    }

    private fun parseQueryInt(query: String, name: String): Int? {
        if (query.isEmpty()) return null
        val q = if (query.startsWith("?")) query.substring(1) else query
        for (pair in q.splitToSequence('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq) == name) return pair.substring(eq + 1).toIntOrNull()
        }
        return null
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }

    private companion object {
        const val SSE_DEFAULT_COUNT = 100
        const val SSE_DEFAULT_SIZE = 1024
    }
}

private val nettyRawUploadAckBytes = "ok".toByteArray()

/**
 * Echoes WebSocket text / binary frames back to the client.
 *
 * Sits in the pipeline after [WebSocketServerProtocolHandler], which
 * handles the HTTP upgrade and frame framing, so this handler only
 * sees fully-decoded [WebSocketFrame]s.
 */
private class WebSocketEchoHandler : SimpleChannelInboundHandler<WebSocketFrame>() {
    override fun channelRead0(ctx: ChannelHandlerContext, frame: WebSocketFrame) {
        when (frame) {
            is TextWebSocketFrame -> ctx.writeAndFlush(TextWebSocketFrame(frame.text()))
            is BinaryWebSocketFrame -> ctx.writeAndFlush(BinaryWebSocketFrame(frame.content().retainedDuplicate()))
            else -> Unit // Close, Ping, Pong, Continuation handled by Netty defaults
        }
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.close()
    }
}
