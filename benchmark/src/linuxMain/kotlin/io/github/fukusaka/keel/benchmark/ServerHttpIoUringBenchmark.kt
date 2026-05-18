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
        val server = keelHttpServer(engine) {
            connector { host = "0.0.0.0"; port = config.port }
            get("/hello") { call -> call.respond(PipelineHttpResponses.hello) }
            get("/large") { call -> call.respond(PipelineHttpResponses.large) }
            // `/ws-deflate` exercises the WS-3 permessage-deflate path:
            // `webSockets(DeflateCodec)` routes the upgrade through the real
            // `runWebSocketUpgrade` negotiation, so the bench measures the
            // productized compression stack rather than a hand-wired one.
            webSockets(DeflateCodec) {
                webSocket("/ws-deflate") { for (m in incoming) send(m) }
            }
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
