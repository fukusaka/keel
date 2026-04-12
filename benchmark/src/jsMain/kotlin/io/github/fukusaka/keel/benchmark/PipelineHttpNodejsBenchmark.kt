package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nodejs.NodeEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
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

        val (tlsBindConfig, tlsCloseable) = if (config.tls != null) createTlsBindConfig(config) else (BindConfig() to null)

        val server = engine.bindPipeline("0.0.0.0", config.port, config = tlsBindConfig) { channel ->
            installPipelineHttpHandlers(channel.pipeline)
        }

        return {
            server.close()
            tlsCloseable?.close()
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
