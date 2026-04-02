package io.github.fukusaka.keel.benchmark

/** Linux default engine — keel-epoll (epoll-based I/O). */
actual fun defaultEngine(): String = "ktor-keel-epoll"

/** Linux engine registry: epoll, io_uring, pipeline HTTP, raw io_uring pipeline, and Ktor CIO. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "ktor-keel-epoll" to KeelEpollEngine,
    "ktor-keel-io-uring" to KeelIoUringEngine,
    "pipeline-http-io-uring" to PipelineHttpIoUringBenchmark,
    "raw-io-uring" to RawIoUringBenchmark,
    "ktor-cio" to CioEngine,
)
