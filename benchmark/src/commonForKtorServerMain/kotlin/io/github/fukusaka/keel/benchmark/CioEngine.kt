package io.github.fukusaka.keel.benchmark

import io.ktor.server.application.serverConfig
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.embeddedServer

/** Ktor CIO engine settings. */
data class CioEngineConfig(
    val idleTimeout: Int? = null,
) : EngineConfig {
    override fun displayTo(sb: StringBuilder, engine: String) {
        val cioDefault = CIOApplicationEngine.Configuration().connectionIdleTimeoutSeconds
        sb.appendLine("--- Engine-Specific (ktor-cio) ---")
        sb.fmtLine("connection-idle-timeout:", "${idleTimeout ?: cioDefault} sec${if (idleTimeout == null) " (default by CIO)" else ""}")
    }

    override fun toString(): String = idleTimeout?.let { "idleTimeout=$it" } ?: ""
}

/** Ktor CIO engine. */
object CioEngine : EngineBenchmark {
    override fun start(config: BenchmarkConfig): () -> Unit {
        val cio = config.engineConfig as? CioEngineConfig ?: CioEngineConfig()
        val rootConfig = serverConfig {
            module { benchmarkModule(config.connectionClose, config.compression) }
        }
        // Connector wiring delegates to the platform-specific
        // [cioConfigureConnector]: JVM calls Ktor's official
        // `sslConnector(keyStore, ...)`, Native uses `connector { ... }`
        // (TLS request raises an error because Ktor 3.4.1 has no
        // multiplatform sslConnector overload yet — see KDoc).
        //
        // No benchmark-level protective guard: Ktor's own
        // `CIOApplicationEngine.kt:224` check throws
        // `UnsupportedOperationException` for `ConnectorType.HTTPS`
        // (upstream issue ktorio/ktor#886 is OPEN), so requesting TLS
        // results in a clean `STARTUP REFUSE` row from the bench
        // harness. If Ktor ever ships CIO Server HTTPS, this code
        // path will start producing real bench numbers automatically
        // without any benchmark-side change.
        val engine = embeddedServer(CIO, rootConfig) {
            cioConfigureConnector(config)
            config.socket.reuseAddress?.let { reuseAddress = it }
            cio.idleTimeout?.let { connectionIdleTimeoutSeconds = it }
        }.start(wait = false)
        return { engine.stop(500, 1000) }
    }

    override fun tunedSocket(s: SocketConfig, cpuCores: Int): SocketConfig = s.copy(
        reuseAddress = s.reuseAddress ?: true,
    )

    override fun tunedConfig(config: BenchmarkConfig, cpuCores: Int): BenchmarkConfig = config.copy(
        engineConfig = CioEngineConfig(idleTimeout = 10)
    )

    override fun mergeConfig(base: EngineConfig, args: Map<String, String>): EngineConfig {
        val b = base as? CioEngineConfig ?: CioEngineConfig()
        return CioEngineConfig(idleTimeout = args["connection-idle-timeout"]?.toInt() ?: b.idleTimeout)
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        val ioP = ioParallelism()
        val cioConfig = CIOApplicationEngine.Configuration()
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "(not configurable, OS: ${os.tcpNoDelay})",
            reuseAddress = "${cioConfig.reuseAddress} (default by CIO)",
            backlog = "(not configurable, OS: ${os.backlog}, estimated)",
            sendBuffer = "(not configurable, OS: ${os.sendBuffer} bytes)",
            receiveBuffer = "(not configurable, OS: ${os.receiveBuffer} bytes)",
            threads = "$ioP (default by Dispatchers.IO)",
        )
    }
}
