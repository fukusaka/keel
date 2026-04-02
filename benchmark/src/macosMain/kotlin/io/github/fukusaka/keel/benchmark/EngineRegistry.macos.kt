package io.github.fukusaka.keel.benchmark

/** macOS default engine — keel-kqueue (kqueue-based I/O). */
actual fun defaultEngine(): String = "ktor-keel-kqueue"

/** macOS engine registry: kqueue, pipeline HTTP, NWConnection, and Ktor CIO. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "ktor-keel-kqueue" to KeelKqueueEngine,
    "pipeline-http-kqueue" to PipelineHttpKqueueBenchmark,
    "ktor-keel-nwconnection" to KeelNwConnectionEngine,
    "ktor-cio" to CioEngine,
)
