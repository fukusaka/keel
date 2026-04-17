package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.epoll.EpollEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.coroutines.runBlocking
/**
 * Pipeline HTTP benchmark using [EpollEngine] with [HttpRequestDecoder],
 * [RoutingHandler], and [HttpResponseEncoder].
 *
 * Same pipeline structure as io_uring and kqueue pipeline benchmarks.
 * Enables direct comparison of epoll vs io_uring pipeline throughput on Linux.
 *
 * Pipeline structure:
 * ```
 * HEAD ↔ [tls] ↔ encoder ↔ decoder ↔ routing ↔ TAIL
 * ```
 */
object PipelineHttpEpollBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = EpollEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = NoopLoggerFactory,
            ),
        )

        val (tlsBindConfig, tlsCloseable) = if (config.tls != null) createTlsBindConfig(config) else (BindConfig() to null)

        val server = engine.bindPipeline("0.0.0.0", config.port, config = tlsBindConfig) { channel ->
            installPipelineHttpHandlers(channel.pipeline)
        }

        return {
            server.close()
            tlsCloseable?.close()
            runBlocking { engine.close() }
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
