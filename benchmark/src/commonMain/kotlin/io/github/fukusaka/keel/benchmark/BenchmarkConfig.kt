package io.github.fukusaka.keel.benchmark

/**
 * Configuration for benchmark servers.
 *
 * ```
 * BenchmarkConfig
 * ├── engine: String               engine identifier
 * ├── port: Int                    server listen port
 * ├── profile: String              "default" | "tuned" | "keel-equiv-0.1"
 * ├── connectionClose: Boolean     force Connection: close on all engines
 * ├── tls: String?                 TLS backend: null (HTTP) | "jsse" | "openssl" | "awslc"
 * ├── tlsInstaller: String         TLS installer: "keel" (default) | "netty"
 * ├── socket: SocketConfig         common socket options (all engines)
 * │   ├── tcpNoDelay               TCP_NODELAY
 * │   ├── reuseAddress             SO_REUSEADDR
 * │   ├── backlog                  SO_BACKLOG
 * │   ├── sendBuffer               SO_SNDBUF
 * │   ├── receiveBuffer            SO_RCVBUF
 * │   └── threads                  worker thread count
 * └── engineConfig: EngineConfig   sealed per-engine settings
 * ```
 *
 * Three built-in profile families:
 * - **default**: Each engine's out-of-box settings (what users experience first)
 * - **tuned**: Maximum performance, auto-calculated from CPU cores and engine type
 * - **keel-equiv-{version}**: Constrain all engines to match a specific keel version
 *
 * Resolution order: CLI arguments > profile presets > engine defaults.
 * Use `--show-config` to display the fully resolved configuration.
 */
/** Default server listen port. */
const val DEFAULT_PORT = 8080

data class BenchmarkConfig(
    val engine: String = defaultEngine(),
    val port: Int = DEFAULT_PORT,
    val profile: String = "default",
    val showConfig: Boolean = false,
    val connectionClose: Boolean = false,
    val tls: String? = null,
    val tlsInstaller: String = "keel",
    val socket: SocketConfig = SocketConfig(),
    val engineConfig: EngineConfig = EngineConfig.None,
) {
    companion object {
        fun parse(args: Array<String>): BenchmarkConfig {
            var config = BenchmarkConfig()
            var socket = SocketConfig()
            val engineArgs = mutableMapOf<String, String>()

            for (arg in args) {
                if (arg == "--show-config") { config = config.copy(showConfig = true); continue }
                val (key, value) = if ("=" in arg) {
                    arg.substringBefore("=").removePrefix("--") to arg.substringAfter("=")
                } else continue

                when (key) {
                    "engine" -> config = config.copy(engine = value)
                    "port" -> config = config.copy(port = value.toInt())
                    "profile" -> config = config.copy(profile = value)
                    "connection-close" -> config = config.copy(connectionClose = value.toBooleanStrict())
                    "tls" -> config = config.copy(tls = value)
                    "tls-installer" -> config = config.copy(tlsInstaller = value)
                    // Socket options
                    "tcp-nodelay" -> socket = socket.copy(tcpNoDelay = value.toBooleanStrict())
                    "reuse-address" -> socket = socket.copy(reuseAddress = value.toBooleanStrict())
                    "backlog" -> socket = socket.copy(backlog = value.toInt())
                    "send-buffer" -> socket = socket.copy(sendBuffer = value.toInt())
                    "receive-buffer" -> socket = socket.copy(receiveBuffer = value.toInt())
                    "threads" -> socket = socket.copy(threads = value.toInt())
                    // Engine-specific (collected, applied later)
                    else -> engineArgs[key] = value
                }
            }

            config = config.copy(socket = socket)
            config = config.applyProfile()
            config = config.copy(
                engineConfig = EngineConfig.merge(config.engine, config.engineConfig, engineArgs)
            )
            return config
        }

        private val VALID_PROFILES = setOf("default", "tuned")

        private fun BenchmarkConfig.applyProfile(): BenchmarkConfig = when {
            profile == "default" -> this
            profile == "tuned" -> applyTuned()
            profile.startsWith("keel-equiv") -> {
                val version = profile.removePrefix("keel-equiv").removePrefix("-")
                applyKeelEquiv(version)
            }
            else -> {
                printErr("Unknown profile: $profile")
                printErr("Available: ${VALID_PROFILES.joinToString(", ")}, keel-equiv-<version>")
                benchmarkExit(1)
            }
        }

        /**
         * Auto-calculate optimal values for maximum throughput.
         *
         * Each engine gets only the overrides that differ from its built-in defaults.
         * Platform-specific tuning (JVM engine types) is delegated to [platformApplyTuned].
         * CLI arguments already parsed into [socket] take precedence via `?:`.
         */
        private fun BenchmarkConfig.applyTuned(): BenchmarkConfig {
            val cpuCores = availableProcessors()
            val eb = engineRegistry()[engine]
            val tunedSocket = eb?.tunedSocket(socket, cpuCores) ?: socket
            var config = copy(socket = tunedSocket)
            config = eb?.tunedConfig(config, cpuCores) ?: config
            return config
        }

        private fun BenchmarkConfig.applyKeelEquiv(version: String): BenchmarkConfig = when (version) {
            "", "0.1" -> copy(connectionClose = true)
            "0.2" -> this
            else -> copy(connectionClose = true)
        }
    }

    fun summary(): String = buildString {
        append("engine=$engine, port=$port, profile=$profile")
        if (tls != null) append(", tls=$tls, tls-installer=$tlsInstaller")
        if (connectionClose) append(", connection=close")
        socket.appendTo(this)
        if (engineConfig !is EngineConfig.None) append(", $engineConfig")
    }

    /**
     * Detailed multi-line display of all resolved settings.
     */
    fun display(): String = buildString {
        appendLine("=== Benchmark Configuration ===")
        fmtLine("engine:", engine)
        fmtLine("port:", "$port")
        fmtLine("profile:", profile)
        fmtLine("tls:", tls ?: "disabled")
        if (tls != null) fmtLine("tls-installer:", tlsInstaller)
        fmtLine("cpu-cores:", "${availableProcessors()}")
        appendLine()
        appendLine("--- Connection ---")
        if (isKeelEngine(engine)) {
            fmtLine("connection-close:", "true (always enforced by keel)")
        } else {
            fmtLine("connection-close:", "$connectionClose")
        }
        appendLine()
        socket.displayTo(this, engine)
        appendLine()
        engineConfig.displayTo(this, engine)
    }
}

/** Check if an engine is a keel engine (always enforces Connection: close in Phase (a)). */
fun isKeelEngine(engine: String): Boolean =
    engine.startsWith("keel-")
