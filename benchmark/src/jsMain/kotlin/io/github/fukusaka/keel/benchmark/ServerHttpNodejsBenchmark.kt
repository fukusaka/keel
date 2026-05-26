package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nodejs.NodeEngine
import io.github.fukusaka.keel.server.http.dsl.keelHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * `keel-server-http` benchmark on [NodeEngine] (Kotlin/JS, Node.js).
 *
 * Runs a `KeelHttpServer` built via the `keelHttpServer { }` DSL — the
 * productized server stack with `Router` resolution + the `HttpCall`
 * dispatch coroutine + middleware chain — on the same Node.js transport
 * as [PipelineHttpNodejsBenchmark] (raw hand-wired codec). Having both
 * on NodeEngine lets a JS profile isolate, on identical transport, the
 * KeelHttpServer DSL overhead vs the raw codec floor.
 *
 * Kotlin/JS has no `runBlocking`, so `server.start()` is fired through a
 * detached `CoroutineScope(Dispatchers.Default).launch` and the Node
 * event loop is allowed to keep the process alive while the listening
 * socket is open (the same pattern PipelineHttpNodejsBenchmark uses).
 */
object ServerHttpNodejsBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val engine = NodeEngine(
            config = IoEngineConfig(
                loggerFactory = benchmarkLoggerFactory(),
            ),
        )
        val (connectorConfigure, tlsCloseable) = serverHttpConnectorConfig(config)
        val server = keelHttpServer(engine) {
            connector(connectorConfigure)
            installBenchCompression(config.compression)
            installStreamingBenchRoutes()
            installWebSocketBenchRoutes()
        }
        // Detached launch — Node has no runBlocking. server.start() is
        // suspend; we let the event loop carry the wait while wrk/k6
        // hit the listening socket.
        CoroutineScope(Dispatchers.Default).launch { server.start() }

        return {
            CoroutineScope(Dispatchers.Default).launch {
                server.stop()
                tlsCloseable?.close()
                engine.close()
            }
        }
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "(not configurable, Node.js default)",
            reuseAddress = "(not configurable, Node.js default)",
            backlog = "(not configurable, Node.js default: 511)",
            sendBuffer = "(not configurable, OS default)",
            receiveBuffer = "(not configurable, OS default)",
            threads = "1 (Node.js single-threaded)",
        )
    }
}
