package io.github.keel.benchmark

/** macOS default engine — keel-kqueue (kqueue-based I/O). */
actual fun defaultEngine(): String = "keel-kqueue"

/** macOS engine registry: kqueue, NWConnection, and Ktor CIO. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "keel-kqueue" to KeelKqueueEngine,
    "keel-nwconnection" to KeelNwConnectionEngine,
    "ktor-cio" to CioEngine,
)
