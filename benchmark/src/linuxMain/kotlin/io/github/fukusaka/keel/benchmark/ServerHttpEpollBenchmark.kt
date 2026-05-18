package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.epoll.EpollEngine
import io.github.fukusaka.keel.server.http.keelHttpServer
import io.github.fukusaka.keel.server.websocket.webSockets
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
