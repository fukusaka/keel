package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.engine.*

/** keel + EpollEngine (Linux default). */
object KeelEpollEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val engine = embeddedServer(Keel, port = config.port) {
            benchmarkModule(config.connectionClose)
        }.start(wait = false)
        return { engine.stop(500, 1000) }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
