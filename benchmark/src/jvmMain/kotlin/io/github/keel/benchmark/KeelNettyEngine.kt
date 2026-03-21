package io.github.keel.benchmark

import io.github.keel.engine.netty.NettyEngine
import io.github.keel.ktor.Keel
import io.ktor.server.application.*
import io.ktor.server.engine.*

/** keel Netty engine — no tunable options. */
object KeelNettyEngine : EngineBenchmark {
    override fun start(config: BenchmarkConfig) {
        val rootConfig = serverConfig {
            module { benchmarkModule(config.connectionClose) }
        }
        embeddedServer(Keel, rootConfig) {
            connector { this.port = config.port }
            engine = NettyEngine()
        }.start(wait = true)
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
