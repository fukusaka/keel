package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.engine.*

/** keel + EpollEngine (Linux default). */
object KeelEpollEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig) {
        embeddedServer(Keel, port = config.port) {
            benchmarkModule(config.connectionClose)
        }.start(wait = true)
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
