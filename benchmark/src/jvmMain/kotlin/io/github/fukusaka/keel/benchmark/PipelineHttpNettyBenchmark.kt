package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpBodyAggregator
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.netty.NettyEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.pipeline.ChannelHandlerContext
import io.github.fukusaka.keel.pipeline.ChannelInboundHandler
/**
 * Pipeline HTTP benchmark using [NettyEngine] with [HttpRequestDecoder],
 * [RoutingHandler], and [HttpResponseEncoder].
 *
 * JVM Netty-based pipeline. Uses Netty's EventLoop for transport and
 * keel's ChannelPipeline for HTTP codec processing.
 *
 * Pipeline structure:
 * ```
 * HEAD ↔ [tls] ↔ encoder ↔ decoder ↔ routing ↔ TAIL
 * ```
 */
object PipelineHttpNettyBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = NettyEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = NoopLoggerFactory,
            ),
        )

        val helloResponse = HttpResponse.ok("Hello, World!", contentType = "text/plain")
        val largeResponse = HttpResponse.ok("x".repeat(LARGE_PAYLOAD_SIZE), contentType = "text/plain")
        helloResponse.headers.size
        largeResponse.headers.size

        val (tlsBindConfig, tlsCloseable) = if (config.tls != null) createTlsBindConfig(config) else (BindConfig() to null)

        val routes: Map<String, (HttpRequestHead) -> HttpResponse> = mapOf(
            "/hello" to { helloResponse },
            "/large" to { largeResponse },
        )

        val server = engine.bindPipeline("0.0.0.0", config.port, config = tlsBindConfig) { channel ->
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("aggregator", HttpBodyAggregator())
            channel.pipeline.addLast("routing", EchoAwareRoutingHandler(routes))
        }

        return {
            server.close()
            tlsCloseable?.close()
            engine.close()
        }
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "(not configurable, Netty default)",
            reuseAddress = "(not configurable, Netty default)",
            backlog = "(not configurable, Netty default: 128)",
            sendBuffer = "(not configurable, OS: ${os.sendBuffer} bytes)",
            receiveBuffer = "(not configurable, OS: ${os.receiveBuffer} bytes)",
            threads = "${Runtime.getRuntime().availableProcessors() * 2} (Netty default: cpu * 2)",
        )
    }
}

/**
 * Routing handler that accepts aggregated [HttpRequest] (with body) from
 * [HttpBodyAggregator]. Falls through to the pre-registered head-only
 * routes for `/hello` and `/large`, and adds `/echo` which echoes the
 * request body.
 */
private class EchoAwareRoutingHandler(
    private val routes: Map<String, (HttpRequestHead) -> HttpResponse>,
) : ChannelInboundHandler {

    override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg !is HttpRequest) {
            ctx.propagateRead(msg)
            return
        }
        // /echo returns the request body as the response body.
        val response = if (msg.path == "/echo") {
            val body = msg.body ?: ByteArray(0)
            HttpResponse.ok(body, contentType = "application/octet-stream")
        } else {
            val handler = routes[msg.path]
            if (handler != null) {
                handler(
                    HttpRequestHead(msg.method, msg.uri, msg.version, msg.headers),
                )
            } else {
                HttpResponse.notFound()
            }
        }
        ctx.propagateWrite(response)
        ctx.propagateFlush()
    }
}
