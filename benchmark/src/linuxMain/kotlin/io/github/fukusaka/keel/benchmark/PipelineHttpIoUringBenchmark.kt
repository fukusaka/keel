package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.iouring.IoModeSelectors
import io.github.fukusaka.keel.engine.iouring.IoUringEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io.github.fukusaka.keel.tls.TlsHandler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Pipeline HTTP benchmark using [IoUringEngine] with [HttpRequestDecoder],
 * [RoutingHandler], and [HttpResponseEncoder].
 *
 * The full pipeline path (decode → route → encode) is exercised on every request,
 * allowing measurement of the complete ChannelPipeline HTTP overhead.
 *
 * Pipeline structure:
 * ```
 * HEAD ↔ [tls] ↔ encoder ↔ decoder ↔ routing ↔ TAIL
 * ```
 * - Inbound (HEAD→TAIL): decoder converts [IoBuf] → [HttpRequestHead] → routing handles it
 * - Outbound (routing→HEAD): encoder converts [HttpResponse] → [IoBuf] → IoTransport
 */
object PipelineHttpIoUringBenchmark : EngineBenchmark {

    @OptIn(ExperimentalForeignApi::class)
    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0 // 0 = auto (availableProcessors)
        val modeSelector = when (getenv("BENCH_IO_MODE")?.toKString()) {
            "cqe" -> IoModeSelectors.CQE
            "fallback" -> IoModeSelectors.FALLBACK_CQE
            "sendzc" -> IoModeSelectors.SEND_ZC
            else -> IoModeSelectors.eagainThreshold() // default: adaptive
        }
        val engine = IoUringEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = NoopLoggerFactory,
            ),
            writeModeSelector = modeSelector,
        )

        // Pre-built responses: headers and body are computed once at startup.
        // flatEntries cache is warmed here (before bindPipeline spawns EventLoop threads)
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

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
