package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.runBlocking

/**
 * `keel-server-http` benchmark on [NioEngine] (JVM).
 *
 * Runs a `KeelHttpServer` built via the `keelHttpServer { }` DSL — the
 * productized server stack with `Router` resolution + the `HttpCall`
 * dispatch coroutine + middleware chain — on the same JVM NIO transport
 * as [PipelineHttpNioBenchmark] (raw hand-wired codec) and
 * [KeelNioEngine] (Ktor on keel). Having all three on NioEngine lets a
 * JFR allocation-by-site profile isolate, on identical transport:
 *
 * - `pipeline-http-nio`: bare codec + cached response (floor)
 * - `server-http-nio`: keel's own framework (Router / HttpCall)
 * - `ktor-keel-nio`: Ktor's framework on keel's engine
 *
 * i.e. keel-server-http vs Ktor framework per-request allocation, with
 * the transport held constant.
 */
object ServerHttpNioBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = NioEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = benchmarkLoggerFactory(),
            ),
        )
        val server = keelHttpServer(engine) {
            connector { host = "0.0.0.0"; port = config.port }
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
