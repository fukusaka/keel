package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.nwconnection.NwEngine
import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer

/** keel + NWConnection (Apple Network.framework). */
object KeelNwConnectionEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val rootConfig = serverConfig {
            module { benchmarkModule(config.connectionClose) }
        }
        val factory = config.tls?.let { createTlsCodecFactory(it) }
        val engine = embeddedServer(Keel, rootConfig) {
            if (factory != null) {
                sslConnector(BenchmarkCertificates.tlsConfig(), factory) { port = config.port }
            } else {
                connector { port = config.port }
            }
            this.engine = NwEngine()
        }.start(wait = false)
        return {
            factory?.close()
            engine.stop(500, 1000)
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
