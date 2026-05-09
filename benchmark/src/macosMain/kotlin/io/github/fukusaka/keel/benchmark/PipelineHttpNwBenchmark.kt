package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nwconnection.NwEngine
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
                loggerFactory = benchmarkLoggerFactory(),
            ),
        )

        val (bindConfig, tlsCloseable) = bindConfigFor(config)

        // NwEngine.bindPipeline() is suspend (listener startup is async).
        val server = runBlocking {
            engine.bindPipeline("0.0.0.0", config.port, config = bindConfig) { channel ->
                installPipelineHttpHandlers(channel.pipeline, compression = config.compression)
            }
        }

        return {
            server.close()
            tlsCloseable?.close()
            runBlocking { engine.close() }
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
