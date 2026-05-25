package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nwconnection.NwEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.runBlocking

/**
 * `keel-server-http` benchmark on [NwEngine] (macOS Network.framework).
 *
 * The NWConnection-transport counterpart of [ServerHttpKqueueBenchmark]:
 * the productized `keelHttpServer { }` stack (Router + HttpCall +
 * middleware) so the keel-framework path is measurable on the push-mode
 * Network.framework engine too, matching the `pipeline-http-*` and
 * `ktor-keel-*` coverage.
 */
object ServerHttpNwConnectionBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = NwEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = benchmarkLoggerFactory(),
            ),
        )
        val (connectorConfigure, tlsCloseable) = serverHttpConnectorConfig(config)
        val server = keelHttpServer(engine) {
            connector(connectorConfigure)
            installStreamingBenchRoutes()
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
