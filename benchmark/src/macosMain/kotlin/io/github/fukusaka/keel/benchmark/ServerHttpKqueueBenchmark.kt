package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.kqueue.KqueueEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.runBlocking

/**
 * `keel-server-http` benchmark on [KqueueEngine] (macOS Native).
 *
 * The macOS Native counterpart of [ServerHttpEpollBenchmark] /
 * [ServerHttpNioBenchmark]: the productized `keelHttpServer { }` stack
 * (Router + HttpCall + middleware) so the keel-framework path is
 * measurable on the kqueue transport, matching the coverage the
 * `pipeline-http-*` and `ktor-keel-*` families already have.
 */
object ServerHttpKqueueBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = KqueueEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = benchmarkLoggerFactory(),
            ),
        )
        val (connectorConfigure, tlsCloseable) = serverHttpConnectorConfig(config)
        val server = keelHttpServer(engine) {
            connector(connectorConfigure)
            installBenchCompression(config.compression)
            installBenchFeatureRoutes(config)
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
