package io.github.fukusaka.keel.benchmark

/** Linux default engine — keel-epoll (epoll-based I/O). */
actual fun defaultEngine(): String = "ktor-keel-epoll"

/**
 * Linux engine registry: epoll, io_uring, pipeline HTTP (epoll + io_uring),
 * keel-server-http (epoll + io_uring), raw io_uring, and Ktor CIO.
 */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "ktor-keel-epoll" to KeelEpollEngine,
    "ktor-keel-io-uring" to KeelIoUringEngine,
    "ktor-cio-keel-epoll" to KeelCioEpollEngine,
    "ktor-cio-keel-io-uring" to KeelCioIoUringEngine,
    "pipeline-http-epoll" to PipelineHttpEpollBenchmark,
    "pipeline-http-io-uring" to PipelineHttpIoUringBenchmark,
    "server-http-epoll" to ServerHttpEpollBenchmark,
    "server-http-io-uring" to ServerHttpIoUringBenchmark,
    "raw-io-uring" to RawIoUringBenchmark,
    "ktor-cio" to CioEngine,
)
