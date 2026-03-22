package io.github.fukusaka.keel.benchmark

/** Linux default engine — keel-epoll (epoll-based I/O). */
actual fun defaultEngine(): String = "ktor-keel-epoll"

/** Linux engine registry: epoll and Ktor CIO. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "ktor-keel-epoll" to KeelEpollEngine,
    "ktor-cio" to CioEngine,
)
