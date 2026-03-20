package io.github.keel.benchmark

/**
 * Configuration for benchmark servers.
 *
 * Three built-in profile families:
 * - **default**: Each engine's out-of-box settings (what users experience first)
 * - **tuned**: Maximum performance settings for each engine
 * - **keel-equiv-{version}**: Constrain all engines to match a specific keel version's limitations
 *
 * The keel-equiv profile is versioned to track keel's evolution:
 * - `keel-equiv-0.1` = Phase (a): Connection: close, sync I/O, no keep-alive
 * - `keel-equiv-0.2` = Phase (b): keep-alive, async I/O (future)
 *
 * Engine-specific parameters are passed through [extras] for extensibility.
 * Any `--key=value` argument not matched by a known key is stored in extras
 * and can be consumed by engine launchers.
 *
 * ```
 * --engine=keel --profile=keel-equiv-0.1
 * --engine=vertx --profile=tuned --threads=8
 * --engine=ktor-netty --tcp-nodelay=true --backlog=2048 --netty.write-buffer-high=131072
 * ```
 */
data class BenchmarkConfig(
    val engine: String = "keel",
    val port: Int = 8080,
    val profile: String = "default",
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
    /** Engine-specific extras not covered by typed fields. Key format: `engine.param`. */
    val extras: Map<String, String> = emptyMap(),
) {
    companion object {
        fun parse(args: Array<String>): BenchmarkConfig {
            var config = BenchmarkConfig()
            val extras = mutableMapOf<String, String>()

            for (arg in args) {
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

            profile == "tuned" -> copy(
                tcpNoDelay = tcpNoDelay ?: true,
                backlog = backlog ?: 1024,
                reuseAddress = reuseAddress ?: true,
            )

            profile.startsWith("keel-equiv") -> {
                val version = profile.removePrefix("keel-equiv").removePrefix("-")
                applyKeelEquiv(version)
            }

            else -> this
        }

        /**
         * Apply keel-equivalent constraints for a specific version.
         *
         * - 0.1 (Phase (a)): Connection: close, sync I/O, no keep-alive
         * - 0.2 (Phase (b)): keep-alive, async I/O (future — placeholder)
         */
        private fun BenchmarkConfig.applyKeelEquiv(version: String): BenchmarkConfig = when (version) {
            "", "0.1" -> copy(connectionClose = true)
            "0.2" -> this // Phase (b): no special constraints (keep-alive supported)
            else -> copy(connectionClose = true) // unknown version defaults to most restrictive
        }
    }

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
}
