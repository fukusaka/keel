package io.github.keel.benchmark

import kotlinx.cinterop.*
import platform.posix.*
import kotlin.experimental.ExperimentalNativeApi

/** Print to stdout — Native stdlib has no stderr API. */
actual fun printErr(message: String) {
    println(message)
}

/** Number of available CPU cores via [Platform.getAvailableProcessors]. */
@OptIn(ExperimentalNativeApi::class)
actual fun availableProcessors(): Int = Platform.getAvailableProcessors()

/** Dispatchers.IO parallelism — hardcoded to 64 to match the JVM default (no system property on Native). */
actual fun ioParallelism(): Int = 64

/** Shared socket defaults for keel Native engines (no tunable options in Phase (a)). */
internal fun keelSocketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
    val ioP = ioParallelism()
    return SocketConfig.SocketDefaults(
        tcpNoDelay = "(not configurable, OS: ${os.tcpNoDelay})",
        reuseAddress = "(not configurable, OS: ${os.reuseAddress})",
        backlog = "(not configurable, OS: ${os.backlog}, estimated)",
        sendBuffer = "(not configurable, OS: ${os.sendBuffer} bytes)",
        receiveBuffer = "(not configurable, OS: ${os.receiveBuffer} bytes)",
        threads = "$ioP (default by Dispatchers.IO)",
    )
}

/**
 * Detect OS socket defaults via POSIX socket + getsockopt.
 *
 * Creates a temporary TCP socket, reads default option values, then closes it.
 * This mirrors the JVM implementation which uses java.net.ServerSocket/Socket.
 */
@OptIn(ExperimentalForeignApi::class)
actual fun detectOsSocketDefaults(): OsSocketDefaults {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) {
        // Fallback to reasonable defaults if socket creation fails
        return OsSocketDefaults(
            tcpNoDelay = false,
            reuseAddress = false,
            backlog = 128,
            sendBuffer = 131072,
            receiveBuffer = 131072,
            fallback = true,
        )
    }
    try {
        return memScoped {
            val optVal = alloc<IntVar>()
            val optLen = alloc<socklen_tVar>()

            fun getIntOpt(level: Int, optName: Int): Int {
                optLen.value = sizeOf<IntVar>().toUInt()
                getsockopt(fd, level, optName, optVal.ptr, optLen.ptr)
                return optVal.value
            }

            OsSocketDefaults(
                tcpNoDelay = getIntOpt(IPPROTO_TCP, TCP_NODELAY) != 0,
                reuseAddress = getIntOpt(SOL_SOCKET, SO_REUSEADDR) != 0,
                backlog = 128, // POSIX has no getsockopt for backlog; 128 is common default
                sendBuffer = getIntOpt(SOL_SOCKET, SO_SNDBUF),
                receiveBuffer = getIntOpt(SOL_SOCKET, SO_RCVBUF),
            )
        }
    } finally {
        close(fd)
    }
}
