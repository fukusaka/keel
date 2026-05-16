package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.iouring.IoUringEngine
import io.github.fukusaka.keel.server.http.keelHttpServer
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
        val server = keelHttpServer(engine) {
            host = "0.0.0.0"
            port = config.port
            get("/hello") { call -> call.respond(PipelineHttpResponses.hello) }
            get("/large") { call -> call.respond(PipelineHttpResponses.large) }
        }
        runBlocking { server.start() }

        return {
            runBlocking {
                server.stop()
                engine.close()
            }
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
