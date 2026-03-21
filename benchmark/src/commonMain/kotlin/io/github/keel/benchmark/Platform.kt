package io.github.keel.benchmark

/**
 * Platform-specific utilities for benchmark configuration.
 *
 * JVM uses java.net.Socket / Runtime APIs; Native uses POSIX socket + getsockopt.
 */

/** Number of available CPU cores. */
expect fun availableProcessors(): Int

/** Dispatchers.IO parallelism (JVM: system property, Native: fixed default). */
expect fun ioParallelism(): Int

/**
 * OS-level socket defaults detected at runtime.
 *
 * JVM: via java.net.ServerSocket / java.net.Socket.
 * Native: via POSIX socket + getsockopt.
 */
data class OsSocketDefaults(
    val tcpNoDelay: Boolean,
    val reuseAddress: Boolean,
    /** Always estimated — no API to query the listen backlog default. */
    val backlog: Int,
    val sendBuffer: Int,
    val receiveBuffer: Int,
    /** true when socket creation failed and all values are fallback estimates. */
    val fallback: Boolean = false,
)

/** Detect OS socket defaults by creating a temporary socket. */
expect fun detectOsSocketDefaults(): OsSocketDefaults

/** Print to stderr (JVM) or stdout (Native). */
expect fun printErr(message: String)

/** Format `"  label                value\n"` with 22-char padded label. */
fun StringBuilder.fmtLine(label: String, value: String) {
    append("  ")
    append(label.padEnd(22))
    append(' ')
    appendLine(value)
}
