package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.TlsCodecServerInstaller
import io.github.fukusaka.keel.server.ktor.cio.KeelCio
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer

/** keel + NIO transport + ktor-http-cio parser (via :keel-server-ktor-cio). */
object KeelCioNioEngine : EngineBenchmark {
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
            this.engine = NioEngine()
            socketOptions = SocketOptions(tcpNoDelay = true)
        }.start(wait = false)
        return {
            factory?.close()
            engine.stop(500, 1000)
        }
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        val ioP = ioParallelism()
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "true (SocketOptions.tcpNoDelay, OS: ${os.tcpNoDelay})",
            reuseAddress = "(not configurable, OS: ${os.reuseAddress})",
            backlog = "(not configurable, OS: ${os.backlog}, estimated)",
            sendBuffer = "(not configurable, OS: ${os.sendBuffer} bytes)",
            receiveBuffer = "(not configurable, OS: ${os.receiveBuffer} bytes)",
            threads = "$ioP (default by Dispatchers.IO)",
        )
    }
}
