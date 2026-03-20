package io.github.keel.benchmark

/**
 * Configuration for benchmark servers.
 *
 * Structure:
 * - [BenchmarkConfig]: top-level (engine name, port, profile)
 * - [SocketConfig]: common socket options shared by all engines
 * - [EngineConfig]: sealed hierarchy for engine-specific tuning
 *
 * Three built-in profile families:
 * - **default**: Each engine's out-of-box settings
 * - **tuned**: Maximum performance, auto-calculated for the runtime
 * - **keel-equiv-{version}**: Constrain all engines to match keel limitations
 *
 * Use `--show-config` to display the resolved configuration.
 */
data class BenchmarkConfig(
    val engine: String = "keel",
    val port: Int = 8080,
    val profile: String = "default",
    val showConfig: Boolean = false,
    val connectionClose: Boolean = false,
    val socket: SocketConfig = SocketConfig(),
    val engineConfig: EngineConfig = EngineConfig.None,
) {
    companion object {
        val cpuCores: Int = Runtime.getRuntime().availableProcessors()

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
            config = config.copy(engineConfig = EngineConfig.parse(config.engine, engineArgs))
            return config
        }

        private fun BenchmarkConfig.applyProfile(): BenchmarkConfig = when {
            profile == "default" -> this
            profile == "tuned" -> applyTuned()
            profile.startsWith("keel-equiv") -> {
                val version = profile.removePrefix("keel-equiv").removePrefix("-")
                applyKeelEquiv(version)
            }
            else -> this
        }

        /**
         * Auto-calculate optimal values based on CPU cores and engine type.
         * CLI arguments (already parsed into [socket]) take precedence via `?:`.
         */
        private fun BenchmarkConfig.applyTuned(): BenchmarkConfig {
            val s = socket
            return copy(
                socket = s.copy(
                    tcpNoDelay = s.tcpNoDelay ?: true,
                    backlog = s.backlog ?: 1024,
                    reuseAddress = s.reuseAddress ?: true,
                    threads = s.threads ?: cpuCores,
                ),
            )
        }

        private fun BenchmarkConfig.applyKeelEquiv(version: String): BenchmarkConfig = when (version) {
            "", "0.1" -> copy(connectionClose = true)
            "0.2" -> this
            else -> copy(connectionClose = true)
        }
    }

    fun summary(): String = buildString {
        append("engine=$engine, port=$port, profile=$profile")
        if (connectionClose) append(", connection=close")
        socket.appendTo(this)
        if (engineConfig !is EngineConfig.None) append(", $engineConfig")
    }

    fun display(): String = buildString {
        appendLine("=== Benchmark Configuration ===")
        appendLine("Engine:     $engine")
        appendLine("Port:       $port")
        appendLine("Profile:    $profile")
        appendLine("CPU cores:  $cpuCores")
        appendLine()
        appendLine("--- Connection ---")
        appendLine("  connection-close: $connectionClose")
        appendLine()
        socket.displayTo(this)
        appendLine()
        engineConfig.displayTo(this, engine)
    }
}

/**
 * Common socket options applicable to all engines.
 * Values are nullable — null means "use engine default".
 */
data class SocketConfig(
    val tcpNoDelay: Boolean? = null,
    val reuseAddress: Boolean? = null,
    val backlog: Int? = null,
    val sendBuffer: Int? = null,
    val receiveBuffer: Int? = null,
    val threads: Int? = null,
) {
    fun appendTo(sb: StringBuilder) {
        tcpNoDelay?.let { sb.append(", tcpNoDelay=$it") }
        reuseAddress?.let { sb.append(", reuseAddress=$it") }
        backlog?.let { sb.append(", backlog=$it") }
        sendBuffer?.let { sb.append(", sendBuffer=$it") }
        receiveBuffer?.let { sb.append(", receiveBuffer=$it") }
        threads?.let { sb.append(", threads=$it") }
    }

    fun displayTo(sb: StringBuilder) {
        sb.appendLine("--- Socket Options ---")
        sb.appendLine("  tcp-nodelay:    ${tcpNoDelay ?: "(engine default)"}")
        sb.appendLine("  reuse-address:  ${reuseAddress ?: "(engine default)"}")
        sb.appendLine("  backlog:        ${backlog ?: "(engine default)"}")
        sb.appendLine("  send-buffer:    ${sendBuffer?.let { "$it bytes" } ?: "(engine default)"}")
        sb.appendLine("  receive-buffer: ${receiveBuffer?.let { "$it bytes" } ?: "(engine default)"}")
        sb.appendLine("  threads:        ${threads ?: "(engine default)"}")
    }
}

/**
 * Engine-specific configuration, type-safe per engine.
 *
 * Each engine variant declares only the parameters it supports.
 * Unknown CLI arguments for an engine are silently ignored.
 * Native engines (Phase 2) add new variants here.
 */
sealed interface EngineConfig {

    fun displayTo(sb: StringBuilder, engine: String)

    /** No engine-specific settings (keel, keel-netty, or unrecognised engine). */
    data object None : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific ($engine) ---")
            when (engine) {
                "keel" -> {
                    sb.appendLine("  Connection: close (always, Phase (a))")
                    sb.appendLine("  I/O: Dispatchers.IO (max 64 threads)")
                    sb.appendLine("  No tunable parameters in Phase (a)")
                }
                "keel-netty" -> {
                    sb.appendLine("  Delegates to keel NettyEngine")
                    sb.appendLine("  No tunable parameters in Phase (a)")
                }
                else -> sb.appendLine("  (no engine-specific parameters)")
            }
        }
    }

    /** Ktor Netty engine settings. */
    data class KtorNetty(
        /** Maximum concurrent requests in pipeline (default: 32). */
        val runningLimit: Int? = null,
        /** Share connection/worker EventLoopGroup (default: false). */
        val shareWorkGroup: Boolean? = null,
    ) : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific (ktor-netty) ---")
            sb.appendLine("  running-limit:    ${runningLimit ?: "32 (default)"}")
            sb.appendLine("  share-work-group: ${shareWorkGroup ?: "false (default)"}")
            sb.appendLine("  configureBootstrap: TCP_NODELAY/SO_BACKLOG from socket config")
            sb.appendLine("  Defaults: workerGroupSize=${BenchmarkConfig.cpuCores / 2 + 1}, callGroupSize=${BenchmarkConfig.cpuCores}")
        }

        override fun toString(): String = buildString {
            runningLimit?.let { append("runningLimit=$it") }
            shareWorkGroup?.let { if (isNotEmpty()) append(", "); append("shareWorkGroup=$it") }
        }
    }

    /** Ktor CIO engine settings. */
    data class Cio(
        /** Idle connection timeout in seconds (default: 45). */
        val idleTimeout: Int? = null,
    ) : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific (cio) ---")
            sb.appendLine("  idle-timeout:     ${idleTimeout ?: "45 (default)"} seconds")
            sb.appendLine("  Defaults: reuseAddress=false")
            sb.appendLine("  No TCP_NODELAY/backlog control (CIO limitation)")
        }

        override fun toString(): String = idleTimeout?.let { "idleTimeout=$it" } ?: ""
    }

    /** Vert.x-specific settings (most are in SocketConfig, this captures overflows). */
    data object Vertx : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific (vertx) ---")
            sb.appendLine("  Defaults: tcpNoDelay=true, soBacklog=1024, eventLoopPoolSize=${BenchmarkConfig.cpuCores}")
            sb.appendLine("  All socket options applied via HttpServerOptions")
        }

        override fun toString(): String = ""
    }

    /** Spring WebFlux settings. */
    data object Spring : EngineConfig {
        override fun displayTo(sb: StringBuilder, engine: String) {
            sb.appendLine("--- Engine-Specific (spring) ---")
            sb.appendLine("  Defaults: reactor.netty.ioWorkerCount=${BenchmarkConfig.cpuCores}")
            sb.appendLine("  Limited socket-level control via application properties")
        }

        override fun toString(): String = ""
    }

    // Future: Native engines
    // data class GoGin(val gomaxprocs: Int? = null) : EngineConfig { ... }
    // data class RustAxum(val workerThreads: Int? = null) : EngineConfig { ... }

    companion object {
        /**
         * Parse engine-specific arguments into the appropriate [EngineConfig] variant.
         */
        fun parse(engine: String, args: Map<String, String>): EngineConfig = when (engine) {
            "ktor-netty" -> KtorNetty(
                runningLimit = args["running-limit"]?.toInt(),
                shareWorkGroup = args["share-work-group"]?.toBooleanStrict(),
            )
            "cio" -> Cio(
                idleTimeout = args["idle-timeout"]?.toInt(),
            )
            "vertx" -> Vertx
            "spring" -> Spring
            else -> None
        }
    }
}
