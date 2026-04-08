package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nodejs.NodeEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.tls.TlsHandler

/**
 * Pipeline HTTP benchmark using [NodeEngine] with [HttpRequestDecoder],
 * [RoutingHandler], and [HttpResponseEncoder].
 *
 * Node.js event-loop-based pipeline. Single-threaded.
 *
 * Pipeline structure:
 * ```
 * HEAD <-> [tls] <-> encoder <-> decoder <-> routing <-> TAIL
 * ```
 */
object PipelineHttpNodejsBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val engine = NodeEngine(
            config = IoEngineConfig(
                loggerFactory = NoopLoggerFactory,
            ),
        )

        val helloResponse = HttpResponse.ok("Hello, World!", contentType = "text/plain")
        val largeResponse = HttpResponse.ok("x".repeat(LARGE_PAYLOAD_SIZE), contentType = "text/plain")
        // Eagerly compute serialized header size to avoid first-request overhead.
        helloResponse.headers.size
        largeResponse.headers.size

        val tlsFactory = config.tls?.let { createTlsCodecFactory(it) }

        val routes: Map<String, (HttpRequestHead) -> HttpResponse> = mapOf(
            "/hello" to { helloResponse },
            "/large" to { largeResponse },
        )

        val server = engine.bindPipeline("0.0.0.0", config.port) { pipeline ->
            pipeline.addLast("encoder", HttpResponseEncoder())
            pipeline.addLast("decoder", HttpRequestDecoder())
            pipeline.addLast("routing", RoutingHandler(routes))
            if (tlsFactory != null) {
                val codec = tlsFactory.createServerCodec(BenchmarkCertificates.tlsConfig())
                pipeline.addFirst("tls", TlsHandler(codec))
            }
        }

        return {
            server.close()
            tlsFactory?.close()
            engine.close()
        }
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "(not configurable, Node.js default)",
            reuseAddress = "(not configurable, Node.js default)",
            backlog = "(not configurable, Node.js default: 511)",
            sendBuffer = "(not configurable, OS default)",
            receiveBuffer = "(not configurable, OS default)",
            threads = "1 (Node.js single-threaded)",
        )
    }
}
