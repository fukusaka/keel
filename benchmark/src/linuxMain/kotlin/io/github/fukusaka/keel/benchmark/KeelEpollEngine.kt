package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.epoll.EpollEngine
import io.github.fukusaka.keel.server.ktor.Keel
import io.github.fukusaka.keel.server.TlsCodecServerInstaller
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer

/** keel + EpollEngine (Linux default). */
object KeelEpollEngine : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val rootConfig = serverConfig {
            module { benchmarkModule(config.connectionClose, config.compression) }
        }
        val factory = config.tls?.let { createTlsCodecFactory(it) }
        val engine = embeddedServer(Keel, rootConfig) {
            if (factory != null) {
                sslConnector(BenchmarkCertificates.tlsConfig(), TlsCodecServerInstaller(factory)) { port = config.port }
            } else {
                connector { this.port = config.port }
            }
            this.engine = EpollEngine()
            // Native ktor-keel compression: keel-codec-http CompressionHandler at engine pipeline level
            pipelineCustomizer = keelNativeCompressionCustomizer(config.compression)
        }.start(wait = false)
        return {
            factory?.close()
            engine.stop(500, 1000)
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
