package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.netty.NettyEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.tls.TlsHandler

/**
 * Pipeline HTTP benchmark using [NettyEngine] with [HttpRequestDecoder],
 * [RoutingHandler], and [HttpResponseEncoder].
 *
 * JVM Netty-based pipeline. Uses Netty's EventLoop for transport and
 * keel's ChannelPipeline for HTTP codec processing.
 *
 * Pipeline structure:
 * ```
 * HEAD ↔ encoder ↔ [tls] ↔ decoder ↔ routing ↔ TAIL
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

        val tlsFactory = config.tls?.let { createTlsCodecFactory(it) }

        val routes: Map<String, (HttpRequestHead) -> HttpResponse> = mapOf(
            "/hello" to { helloResponse },
            "/large" to { largeResponse },
        )

        val server = engine.bindPipeline("0.0.0.0", config.port) { pipeline ->
            pipeline.addLast("encoder", HttpResponseEncoder())
            if (tlsFactory != null) {
                val codec = tlsFactory.createServerCodec(BenchmarkCertificates.tlsConfig())
                pipeline.addLast("tls", TlsHandler(codec))
            }
            pipeline.addLast("decoder", HttpRequestDecoder())
            pipeline.addLast("routing", RoutingHandler(routes))
        }

        return {
            server.close()
            tlsFactory?.close()
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
