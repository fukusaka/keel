package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.kqueue.KqueueEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.tls.TlsHandler

/**
 * Pipeline HTTP benchmark using [KqueueEngine] with [HttpRequestDecoder],
 * [RoutingHandler], and [HttpResponseEncoder].
 *
 * Same pipeline structure as [PipelineHttpIoUringBenchmark] but using
 * kqueue-based I/O on macOS. Allows direct comparison of pipeline
 * throughput between kqueue (macOS) and io_uring (Linux).
 *
 * Pipeline structure (addLast order, outbound propagation travels toward HEAD):
 * ```
 * HEAD ↔ encoder ↔ [tls] ↔ decoder ↔ routing ↔ TAIL
 * ```
 */
object PipelineHttpKqueueBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0 // 0 = auto (availableProcessors)
        val engine = KqueueEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = NoopLoggerFactory,
            ),
        )

        // Pre-built responses: headers and body are computed once at startup.
        // flatEntries cache is warmed here (before EventLoop threads process requests)
        // to avoid benign but unnecessary first-request computation on each thread.
        val helloResponse = HttpResponse.ok("Hello, World!", contentType = "text/plain")
        val largeResponse = HttpResponse.ok("x".repeat(LARGE_PAYLOAD_SIZE), contentType = "text/plain")
        helloResponse.headers.size // warm flatEntries cache
        largeResponse.headers.size // warm flatEntries cache

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

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
