package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.iouring.IoUringEngine
import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.application.*
import io.ktor.server.engine.*

/** keel + IoUringEngine (Linux io_uring). */
object KeelIoUringEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig) {
        val rootConfig = serverConfig {
            module { benchmarkModule(config.connectionClose) }
        }
        embeddedServer(Keel, rootConfig) {
            connector { port = config.port }
            engine = IoUringEngine()
        }.start(wait = true)
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
