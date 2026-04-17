package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
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

        val (tlsBindConfig, tlsCloseable) = if (config.tls != null) createTlsBindConfig(config) else (BindConfig() to null)

        val server = runBlocking {
            engine.bindPipeline("0.0.0.0", config.port, config = tlsBindConfig) { channel ->
                installPipelineHttpHandlers(channel.pipeline)
            }
        }

        return {
            server.close()
            tlsCloseable?.close()
            runBlocking { engine.close() }
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
