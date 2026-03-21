package io.github.keel.benchmark

/** Linux default engine — keel-epoll (epoll-based I/O). */
actual fun defaultEngine(): String = "keel-epoll"

/** Linux engine registry: epoll and Ktor CIO. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "keel-epoll" to KeelEpollEngine,
    "ktor-cio" to CioEngine,
)
