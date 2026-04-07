package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.tls.TlsHandler
import kotlinx.coroutines.runBlocking

/**
 * Pipeline HTTP benchmark using [NioEngine] with [HttpRequestDecoder],
 * [RoutingHandler], and [HttpResponseEncoder].
 *
 * JVM NIO Selector-based pipeline. Runs on both macOS and Linux.
 * NioEngine.bindPipeline() is suspend (Selector channel registration
 * requires EventLoop thread), so wrapped in runBlocking.
 *
 * Pipeline structure:
 * ```
 * HEAD ↔ [tls] ↔ encoder ↔ decoder ↔ routing ↔ TAIL
 * ```
 */
object PipelineHttpNioBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = NioEngine(
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

        val server = runBlocking {
            engine.bindPipeline("0.0.0.0", config.port) { pipeline ->
                pipeline.addLast("encoder", HttpResponseEncoder())
                pipeline.addLast("decoder", HttpRequestDecoder())
                pipeline.addLast("routing", RoutingHandler(routes))
                if (tlsFactory != null) {
                    val codec = tlsFactory.createServerCodec(BenchmarkCertificates.tlsConfig())
                    pipeline.addFirst("tls", TlsHandler(codec))
                }
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
            tcpNoDelay = "(not configurable, OS: ${os.tcpNoDelay})",
            reuseAddress = "(not configurable, OS: ${os.reuseAddress})",
            backlog = "(not configurable, OS: ${os.backlog}, estimated)",
            sendBuffer = "(not configurable, OS: ${os.sendBuffer} bytes)",
            receiveBuffer = "(not configurable, OS: ${os.receiveBuffer} bytes)",
            threads = "${Runtime.getRuntime().availableProcessors()} (default by availableProcessors)",
        )
    }
}
