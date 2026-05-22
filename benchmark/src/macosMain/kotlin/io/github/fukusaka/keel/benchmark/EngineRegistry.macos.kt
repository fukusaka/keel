package io.github.fukusaka.keel.benchmark

/** macOS default engine — keel-kqueue (kqueue-based I/O). */
actual fun defaultEngine(): String = "ktor-keel-kqueue"

/** macOS engine registry: kqueue, NWConnection, pipeline HTTP variants, and Ktor CIO. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "ktor-keel-kqueue" to KeelKqueueEngine,
    "ktor-cio-keel-kqueue" to KeelCioKqueueEngine,
    "pipeline-http-kqueue" to PipelineHttpKqueueBenchmark,
    "server-http-kqueue" to ServerHttpKqueueBenchmark,
    "ktor-keel-nwconnection" to KeelNwConnectionEngine,
    "ktor-cio-keel-nwconnection" to KeelCioNwConnectionEngine,
    "pipeline-http-nwconnection" to PipelineHttpNwBenchmark,
    "server-http-nwconnection" to ServerHttpNwConnectionBenchmark,
    "ktor-cio" to CioEngine,
)
