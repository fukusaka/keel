package io.github.keel.benchmark

/**
 * Configuration for benchmark servers.
 *
 * Three built-in profiles:
 * - **default**: Each engine's out-of-box settings (what users experience first)
 * - **tuned**: Maximum performance settings for each engine
 * - **keel-equiv**: Constrain all engines to match keel Phase (a) limitations
 *   (Connection: close, no TCP_NODELAY override)
 */
data class BenchmarkConfig(
    val engine: String = "keel",
    val port: Int = 8080,
    val profile: String = "default",
    /** Force Connection: close on all engines (keel-equiv profile sets this to true). */
    val connectionClose: Boolean = false,
    /** Set TCP_NODELAY on server sockets (tuned profile sets this to true). */
    val tcpNoDelay: Boolean? = null,
    /** Listen backlog size (tuned profile increases this). */
    val backlog: Int? = null,
    /** Thread count for engines that support it (Ktor Netty worker threads, Vert.x event loops). */
    val threads: Int? = null,
) {
    companion object {
        fun parse(args: Array<String>): BenchmarkConfig {
            var config = BenchmarkConfig()

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
                    "backlog" -> config.copy(backlog = value.toInt())
                    "threads" -> config.copy(threads = value.toInt())
                    else -> config
                }
            }

            return config.applyProfile()
        }

        /**
         * Apply profile presets. Explicit CLI arguments take precedence
         * (already set before applyProfile is called, and copy() only
         * overrides null/default values).
         */
        private fun BenchmarkConfig.applyProfile(): BenchmarkConfig = when (profile) {
            "default" -> this
            "tuned" -> copy(
                connectionClose = connectionClose,  // keep explicit override if set
                tcpNoDelay = tcpNoDelay ?: true,
                backlog = backlog ?: 1024,
            )
            "keel-equiv" -> copy(
                connectionClose = true,
            )
            else -> this
        }
    }

    fun summary(): String = buildString {
        append("engine=$engine, port=$port, profile=$profile")
        if (connectionClose) append(", connection=close")
        tcpNoDelay?.let { append(", tcpNoDelay=$it") }
        backlog?.let { append(", backlog=$it") }
        threads?.let { append(", threads=$it") }
    }
}
