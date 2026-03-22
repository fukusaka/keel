package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.nwconnection.NwEngine
import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.application.*
import io.ktor.server.engine.*

/** keel + NWConnection (Apple Network.framework). */
object KeelNwConnectionEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig) {
        val rootConfig = serverConfig {
            module { benchmarkModule(config.connectionClose) }
        }
        embeddedServer(Keel, rootConfig) {
            connector { port = config.port }
            engine = NwEngine()
        }.start(wait = true)
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
