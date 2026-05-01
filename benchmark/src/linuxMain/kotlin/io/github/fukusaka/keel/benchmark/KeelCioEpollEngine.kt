package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.epoll.EpollEngine
import io.github.fukusaka.keel.server.TlsCodecServerInstaller
import io.github.fukusaka.keel.server.ktor.cio.KeelCio
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer

/** keel + EpollEngine transport + ktor-http-cio parser (Pattern C, Linux). */
object KeelCioEpollEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val rootConfig = serverConfig {
            module { benchmarkModule(config.connectionClose, config.compression) }
        }
        val factory = config.tls?.let { createTlsCodecFactory(it) }
        val engine = embeddedServer(KeelCio, rootConfig) {
            if (factory != null) {
                sslConnector(BenchmarkCertificates.tlsConfig(), TlsCodecServerInstaller(factory)) { port = config.port }
            } else {
                connector { this.port = config.port }
            }
            this.engine = EpollEngine()
        }.start(wait = false)
        return {
            factory?.close()
            engine.stop(500, 1000)
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
