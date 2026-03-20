package io.github.keel.benchmark

/**
 * Configuration for benchmark servers.
 *
 * Three built-in profile families:
 * - **default**: Each engine's out-of-box settings (what users experience first)
 * - **tuned**: Maximum performance settings auto-calculated for the runtime environment
 * - **keel-equiv-{version}**: Constrain all engines to match a specific keel version's limitations
 *
 * The tuned profile auto-detects CPU cores and calculates optimal thread counts,
 * backlog, and buffer sizes per engine. CLI arguments override auto-calculated values.
 *
 * The keel-equiv profile is versioned to track keel's evolution:
 * - `keel-equiv-0.1` = Phase (a): Connection: close, sync I/O, no keep-alive
 * - `keel-equiv-0.2` = Phase (b): keep-alive, async I/O (future)
 *
 * Engine-specific parameters are passed through [extras] for extensibility.
 * Any `--key=value` argument not matched by a known key is stored in extras
 * and can be consumed by engine launchers (including future Native engines).
 *
 * Use `--show-config` to display the resolved configuration without starting a server.
 */
data class BenchmarkConfig(
    val engine: String = "keel",
    val port: Int = 8080,
    val profile: String = "default",
    /** Display resolved config and exit without starting the server. */
    val showConfig: Boolean = false,
    /** Force Connection: close on all engines. */
    val connectionClose: Boolean = false,
    /** Set TCP_NODELAY on server sockets. */
    val tcpNoDelay: Boolean? = null,
    /** SO_REUSEADDR on server sockets. */
    val reuseAddress: Boolean? = null,
    /** Listen backlog size (SO_BACKLOG). */
    val backlog: Int? = null,
    /** SO_SNDBUF size in bytes. */
    val sendBuffer: Int? = null,
    /** SO_RCVBUF size in bytes. */
    val receiveBuffer: Int? = null,
    /** Thread count for engines that support it. */
    val threads: Int? = null,
    /** Ktor Netty: maximum concurrent requests in pipeline. */
    val runningLimit: Int? = null,
    /** Ktor Netty: share connection/worker groups (reduces threads). */
    val shareWorkGroup: Boolean? = null,
    /** CIO: idle connection timeout in seconds. */
    val idleTimeout: Int? = null,
    /** Engine-specific extras not covered by typed fields. */
    val extras: Map<String, String> = emptyMap(),
) {
    companion object {
        private val cpuCores = Runtime.getRuntime().availableProcessors()

        fun parse(args: Array<String>): BenchmarkConfig {
            var config = BenchmarkConfig()
            val extras = mutableMapOf<String, String>()

            for (arg in args) {
                if (arg == "--show-config") {
                    config = config.copy(showConfig = true)
                    continue
                }
                val (key, value) = if ("=" in arg) {
                    arg.substringBefore("=").removePrefix("--") to arg.substringAfter("=")
                } else continue

                config = when (key) {
                    "engine" -> config.copy(engine = value)
                    "port" -> config.copy(port = value.toInt())
                    "profile" -> config.copy(profile = value)
                    "connection-close" -> config.copy(connectionClose = value.toBooleanStrict())
                    "tcp-nodelay" -> config.copy(tcpNoDelay = value.toBooleanStrict())
                    "reuse-address" -> config.copy(reuseAddress = value.toBooleanStrict())
                    "backlog" -> config.copy(backlog = value.toInt())
                    "send-buffer" -> config.copy(sendBuffer = value.toInt())
                    "receive-buffer" -> config.copy(receiveBuffer = value.toInt())
                    "threads" -> config.copy(threads = value.toInt())
                    "running-limit" -> config.copy(runningLimit = value.toInt())
                    "share-work-group" -> config.copy(shareWorkGroup = value.toBooleanStrict())
                    "idle-timeout" -> config.copy(idleTimeout = value.toInt())
                    else -> {
                        extras[key] = value
                        config
                    }
                }
            }

            return config.copy(extras = extras).applyProfile()
        }

        /**
         * Apply profile presets. Explicit CLI arguments (already parsed) take
         * precedence over profile defaults via the `?:` (elvis) pattern.
         */
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
         * Auto-calculate optimal values based on runtime environment and engine.
         *
         * Tuning strategy per engine:
         * - Ktor Netty: high thread counts, TCP_NODELAY, large backlog
         * - Ktor CIO: limited knobs — reuseAddress and shorter idle timeout
         * - Spring WebFlux: I/O worker count = CPU cores
         * - Vert.x: event loop = CPU cores, TCP_NODELAY (already default true)
         * - keel: limited knobs in Phase (a)
         */
        private fun BenchmarkConfig.applyTuned(): BenchmarkConfig {
            // Common optimisations across all engines
            var config = copy(
                tcpNoDelay = tcpNoDelay ?: true,
                backlog = backlog ?: 1024,
                reuseAddress = reuseAddress ?: true,
            )

            // Engine-specific auto-tuning
            config = when (engine) {
                "ktor-netty" -> config.copy(
                    threads = config.threads ?: cpuCores,
                    runningLimit = config.runningLimit ?: (cpuCores * 16),
                    shareWorkGroup = config.shareWorkGroup ?: false,
                )
                "cio" -> config.copy(
                    idleTimeout = config.idleTimeout ?: 10,
                )
                "vertx" -> config.copy(
                    threads = config.threads ?: cpuCores,
                )
                "spring" -> config.copy(
                    threads = config.threads ?: cpuCores,
                )
                else -> config
            }

            return config
        }

        /**
         * Apply keel-equivalent constraints for a specific version.
         *
         * - 0.1 (Phase (a)): Connection: close, sync I/O, no keep-alive
         * - 0.2 (Phase (b)): keep-alive, async I/O (future — placeholder)
         */
        private fun BenchmarkConfig.applyKeelEquiv(version: String): BenchmarkConfig = when (version) {
            "", "0.1" -> copy(connectionClose = true)
            "0.2" -> this
            else -> copy(connectionClose = true)
        }
    }

    /** One-line summary for log output. */
    fun summary(): String = buildString {
        append("engine=$engine, port=$port, profile=$profile")
        if (connectionClose) append(", connection=close")
        tcpNoDelay?.let { append(", tcpNoDelay=$it") }
        reuseAddress?.let { append(", reuseAddress=$it") }
        backlog?.let { append(", backlog=$it") }
        sendBuffer?.let { append(", sendBuffer=$it") }
        receiveBuffer?.let { append(", receiveBuffer=$it") }
        threads?.let { append(", threads=$it") }
        runningLimit?.let { append(", runningLimit=$it") }
        shareWorkGroup?.let { append(", shareWorkGroup=$it") }
        idleTimeout?.let { append(", idleTimeout=$it") }
        if (extras.isNotEmpty()) append(", extras=$extras")
    }

    /**
     * Detailed multi-line display of all resolved settings.
     * Shows which values were auto-calculated vs explicitly set.
     */
    fun display(): String = buildString {
        val cpus = Runtime.getRuntime().availableProcessors()
        appendLine("=== Benchmark Configuration ===")
        appendLine("Engine:          $engine")
        appendLine("Port:            $port")
        appendLine("Profile:         $profile")
        appendLine("CPU cores:       $cpus")
        appendLine()
        appendLine("--- Connection ---")
        appendLine("  connection-close: $connectionClose")
        appendLine("  idle-timeout:     ${idleTimeout ?: "(engine default)"}")
        appendLine()
        appendLine("--- Socket Options ---")
        appendLine("  tcp-nodelay:      ${tcpNoDelay ?: "(engine default)"}")
        appendLine("  reuse-address:    ${reuseAddress ?: "(engine default)"}")
        appendLine("  backlog:          ${backlog ?: "(engine default)"}")
        appendLine("  send-buffer:      ${sendBuffer?.let { "$it bytes" } ?: "(engine default)"}")
        appendLine("  receive-buffer:   ${receiveBuffer?.let { "$it bytes" } ?: "(engine default)"}")
        appendLine()
        appendLine("--- Threading ---")
        appendLine("  threads:          ${threads ?: "(engine default)"}")
        appendLine("  running-limit:    ${runningLimit ?: "(engine default)"}")
        appendLine("  share-work-group: ${shareWorkGroup ?: "(engine default)"}")
        appendLine()
        appendLine("--- Engine Defaults (when not overridden) ---")
        when (engine) {
            "keel" -> {
                appendLine("  Connection: close (always, Phase (a))")
                appendLine("  I/O: Dispatchers.IO (max 64 threads)")
            }
            "cio" -> {
                appendLine("  connectionIdleTimeoutSeconds: 45")
                appendLine("  reuseAddress: false")
                appendLine("  No TCP_NODELAY/backlog/threads control")
            }
            "ktor-netty" -> {
                appendLine("  workerGroupSize: ${cpus / 2 + 1}")
                appendLine("  callGroupSize: $cpus")
                appendLine("  runningLimit: 32")
                appendLine("  tcpKeepAlive: false")
                appendLine("  TCP_NODELAY/SO_BACKLOG: via configureBootstrap")
            }
            "spring" -> {
                appendLine("  reactor.netty.ioWorkerCount: $cpus")
                appendLine("  Limited socket-level control via properties")
            }
            "vertx" -> {
                appendLine("  eventLoopPoolSize: $cpus")
                appendLine("  tcpNoDelay: true (Vert.x default)")
                appendLine("  soBacklog: 1024")
            }
        }
        if (extras.isNotEmpty()) {
            appendLine()
            appendLine("--- Extras ---")
            extras.forEach { (k, v) -> appendLine("  $k: $v") }
        }
    }
}
