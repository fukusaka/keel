package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.epoll.EpollEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.server.websocket.dsl.webSockets
import kotlinx.coroutines.runBlocking

/**
 * `keel-server-http` benchmark on [EpollEngine].
 *
 * Runs a `KeelHttpServer` built via the `keelHttpServer { }` DSL with the
 * `/hello` and `/large` routes wired to the pre-built
 * [PipelineHttpResponses]. This is the productized server stack — `Router`
 * resolution plus the `HttpCall` dispatch coroutine — and is the
 * counterpart of [PipelineHttpEpollBenchmark]'s raw hand-wired pipeline:
 * comparing the two isolates the keel-server-http per-request overhead.
 */
object ServerHttpEpollBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = EpollEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = benchmarkLoggerFactory(),
            ),
        )
        val (connectorConfigure, tlsCloseable) = serverHttpConnectorConfig(config)
        val server = keelHttpServer(engine) {
            connector(connectorConfigure)
            installStreamingBenchRoutes()
            installWebSocketBenchRoutes()
        }
        runBlocking { server.start() }

        return {
            runBlocking {
                server.stop()
                tlsCloseable?.close()
                engine.close()
            }
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
