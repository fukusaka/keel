package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nwconnection.NwEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.coroutines.runBlocking

/**
 * Pipeline HTTP benchmark using [NwEngine] with [HttpRequestDecoder],
 * [RoutingHandler], and [HttpResponseEncoder].
 *
 * NWConnection's read path copies from `dispatch_data_t` (non-zero-copy),
 * so throughput is expected to be lower than kqueue pipeline.
 *
 * Pipeline structure:
 * ```
 * HEAD ↔ [tls] ↔ encoder ↔ decoder ↔ routing ↔ TAIL
 * ```
 */
object PipelineHttpNwBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val engine = NwEngine(
            config = IoEngineConfig(
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

        // NwEngine.bindPipeline() is suspend (listener startup is async).
        val server = runBlocking {
            engine.bindPipeline("0.0.0.0", config.port) { channel ->
                if (tlsFactory != null) {
                    tlsFactory.install(channel, BenchmarkCertificates.tlsConfig())
                }
                channel.pipeline.addLast("encoder", HttpResponseEncoder())
                channel.pipeline.addLast("decoder", HttpRequestDecoder())
                channel.pipeline.addLast("routing", RoutingHandler(routes))
            }
        }

        return {
            server.close()
            tlsFactory?.close()
            engine.close()
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
