package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.kqueue.KqueueEngine
import io.github.fukusaka.keel.server.TlsCodecServerInstaller
import io.github.fukusaka.keel.server.ktor.cio.KeelCio
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer

/** keel + KqueueEngine transport + ktor-http-cio parser (via :keel-server-ktor-cio, macOS). */
object KeelCioKqueueEngine : EngineBenchmark {

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
            this.engine = KqueueEngine()
        }.start(wait = false)
        return {
            factory?.close()
            engine.stop(500, 1000)
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
