package io.github.fukusaka.keel.benchmark

/**
 * Common TCP/IP socket options shared by all engines.
 *
 * All values are nullable: `null` means "use the engine's built-in default".
 * This allows the `tuned` profile to set values via `?:` without overriding
 * explicit CLI arguments that were already parsed.
 */
data class SocketConfig(
    /** Disable Nagle's algorithm for lower latency (TCP_NODELAY). */
    val tcpNoDelay: Boolean? = null,
    /** Allow binding to a port in TIME_WAIT state (SO_REUSEADDR). */
    val reuseAddress: Boolean? = null,
    /** Maximum length of the pending connection queue (SO_BACKLOG). */
    val backlog: Int? = null,
    /** Kernel send buffer size in bytes (SO_SNDBUF). */
    val sendBuffer: Int? = null,
    /** Kernel receive buffer size in bytes (SO_RCVBUF). */
    val receiveBuffer: Int? = null,
    /** Worker thread count. Meaning varies by engine (event loops, I/O workers, etc.). */
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

    /**
     * Display socket options with engine-specific default values shown in parentheses.
     */
    fun displayTo(sb: StringBuilder, engine: String = "") {
        val d = engineDefaults(engine)
        sb.appendLine("--- Socket Options ---")
        if (d.fallback) sb.appendLine("  ** OS socket detection failed; values below are estimates **")
        sb.fmtLine("tcp-nodelay:", tcpNoDelay?.toString() ?: d.tcpNoDelay)
        sb.fmtLine("reuse-address:", reuseAddress?.toString() ?: d.reuseAddress)
        sb.fmtLine("backlog:", backlog?.toString() ?: d.backlog)
        sb.fmtLine("send-buffer:", sendBuffer?.let { "$it bytes" } ?: d.sendBuffer)
        sb.fmtLine("receive-buffer:", receiveBuffer?.let { "$it bytes" } ?: d.receiveBuffer)
        val threadsDisplay = when {
            threads != null && threads == availableProcessors() -> "$threads (tuned: cpu-cores)"
            threads != null -> "$threads"
            else -> d.threads
        }
        sb.fmtLine("threads:", threadsDisplay)
    }

    companion object {
        /** OS-level socket defaults, read once at startup via a temporary socket. */
        private val osDefaults: OsSocketDefaults by lazy { detectOsSocketDefaults() }

        /** Resolve socket defaults via engine registry and OS detection. */
        fun engineDefaults(engine: String): SocketDefaults {
            val os = osDefaults
            val defaults = engineRegistry()[engine]?.socketDefaults(os) ?: SocketDefaults()
            return if (os.fallback) defaults.copy(fallback = true) else defaults
        }
    }

    data class SocketDefaults(
        val tcpNoDelay: String = "(engine default)",
        val reuseAddress: String = "(engine default)",
        val backlog: String = "(engine default)",
        val sendBuffer: String = "(engine default)",
        val receiveBuffer: String = "(engine default)",
        val threads: String = "(engine default)",
        /** true when OS socket detection failed and all OS values are estimates. */
        val fallback: Boolean = false,
    )
}
