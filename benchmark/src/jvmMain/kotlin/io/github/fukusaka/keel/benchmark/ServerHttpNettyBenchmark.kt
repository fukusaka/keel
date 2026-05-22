package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.netty.NettyEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.runBlocking

/**
 * `keel-server-http` benchmark on [NettyEngine] (JVM).
 *
 * The Netty-transport counterpart of [ServerHttpNioBenchmark]: the
 * productized `keelHttpServer { }` stack (Router + HttpCall +
 * middleware) so the keel-framework path is measurable on the Netty
 * engine, matching the `pipeline-http-*` and `ktor-keel-*` coverage.
 */
object ServerHttpNettyBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = NettyEngine(
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
