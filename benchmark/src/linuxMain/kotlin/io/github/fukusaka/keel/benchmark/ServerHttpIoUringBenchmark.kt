package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.iouring.IoUringEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import io.github.fukusaka.keel.server.websocket.dsl.webSockets
import kotlinx.coroutines.runBlocking

/**
 * `keel-server-http` benchmark on [IoUringEngine].
 *
 * The io_uring counterpart of [ServerHttpEpollBenchmark]. Uses the
 * engine's auto-detected io_uring capabilities (no per-capability env
 * knobs — those belong to the raw [PipelineHttpIoUringBenchmark]); this
 * variant exists to measure the keel-server-http per-request overhead on
 * the io_uring transport.
 */
object ServerHttpIoUringBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = IoUringEngine(
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
