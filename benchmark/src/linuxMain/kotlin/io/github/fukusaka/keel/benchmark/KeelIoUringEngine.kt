package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.iouring.IoUringEngine
import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.application.*
import io.ktor.server.engine.*

/** keel + IoUringEngine (Linux io_uring). */
object KeelIoUringEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val rootConfig = serverConfig {
            module { benchmarkModule(config.connectionClose) }
        }
        val engine = embeddedServer(Keel, rootConfig) {
            connector { port = config.port }
            this.engine = IoUringEngine()
        }.start(wait = false)
        return { engine.stop(500, 1000) }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
