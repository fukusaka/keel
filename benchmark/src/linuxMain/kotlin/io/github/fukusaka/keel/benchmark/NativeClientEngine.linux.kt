package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.engine.epoll.EpollEngine
import io.github.fukusaka.keel.engine.iouring.IoUringEngine

/**
 * Linux engines the client benchmark can drive.
 *
 * epoll and io_uring differ in where the I/O work happens — readiness
 * notification followed by a syscall per operation, versus submitted operations
 * completed by the kernel — and a client's request/response cycle stresses that
 * difference differently from a server's accept-and-serve loop. Both are
 * offered so the client side can be measured on its own terms rather than
 * inheriting the server sweep's conclusion.
 */
internal actual fun nativeClientEngineName(clientType: String): String = when (clientType) {
    "keel", "keel-epoll" -> EPOLL
    "keel-io-uring" -> IO_URING
    else -> error(
        "unsupported --client-type='$clientType' on Linux " +
            "(expected keel, keel-epoll or keel-io-uring)",
    )
}

internal actual fun createNativeClientEngine(engineName: String): StreamEngine {
    val config = IoEngineConfig(loggerFactory = benchmarkLoggerFactory())
    return when (engineName) {
        EPOLL -> EpollEngine(config = config)
        IO_URING -> IoUringEngine(config = config)
        else -> error("unknown Linux client engine '$engineName'")
    }
}

private const val EPOLL = "epoll"
private const val IO_URING = "io-uring"
