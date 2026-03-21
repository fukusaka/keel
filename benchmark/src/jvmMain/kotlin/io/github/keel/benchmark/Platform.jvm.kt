package io.github.keel.benchmark

/** Print to stderr via [System.err]. */
actual fun printErr(message: String) {
    System.err.println(message)
}

/** Number of available CPU cores via [Runtime.availableProcessors]. */
actual fun availableProcessors(): Int = Runtime.getRuntime().availableProcessors()

/** Ktor CIO I/O parallelism — reads `kotlinx.coroutines.io.parallelism` system property, defaults to 64. */
actual fun ioParallelism(): Int =
    System.getProperty("kotlinx.coroutines.io.parallelism")?.toIntOrNull() ?: 64

/**
 * Detect OS socket defaults by opening temporary [java.net.ServerSocket] and [java.net.Socket].
 *
 * Backlog is hardcoded to 50 (Java ServerSocket documented default) since there is
 * no API to query the kernel's actual listen backlog.
 */
actual fun detectOsSocketDefaults(): OsSocketDefaults {
    val ss = java.net.ServerSocket()
    val sock = java.net.Socket()
    try {
        return OsSocketDefaults(
            tcpNoDelay = sock.tcpNoDelay,
            reuseAddress = ss.reuseAddress,
            backlog = 50, // Java ServerSocket default (documented in ServerSocket javadoc)
            sendBuffer = sock.sendBufferSize,
            receiveBuffer = ss.receiveBufferSize,
        )
    } finally {
        sock.close()
        ss.close()
    }
}
