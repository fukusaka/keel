package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.engine.*

/** keel NIO engine — no tunable options. */
object KeelNioEngine : EngineBenchmark {
    override fun start(config: BenchmarkConfig): () -> Unit {
        val engine = embeddedServer(Keel, port = config.port) {
            benchmarkModule(config.connectionClose)
        }.start(wait = false)
        return { engine.stop(500, 1000) }
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        val ioP = ioParallelism()
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "(not configurable, OS: ${os.tcpNoDelay})",
            reuseAddress = "(not configurable, OS: ${os.reuseAddress})",
            backlog = "(not configurable, OS: ${os.backlog}, estimated)",
            sendBuffer = "(not configurable, OS: ${os.sendBuffer} bytes)",
            receiveBuffer = "(not configurable, OS: ${os.receiveBuffer} bytes)",
            threads = "$ioP (default by Dispatchers.IO)",
        )
    }
}
