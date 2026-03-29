package io.github.fukusaka.keel.benchmark

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.signal
import platform.posix.sleep
import kotlin.concurrent.AtomicInt

/** Atomic flag set by signal handler to request shutdown. */
private val shutdownRequested = AtomicInt(0)

/**
 * Kotlin/Native benchmark server entry point.
 *
 * Uses the shared [BenchmarkConfig] from commonMain with platform-specific
 * [engineRegistry] for engine selection.
 *
 * Registers SIGTERM/SIGINT handlers for graceful shutdown. When a signal
 * is received, the server is stopped cleanly so the listen socket is
 * closed and the port is freed immediately.
 */
@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    // GC tuning via --gc-target=<bytes> (e.g. --gc-target=256m)
    applyGcTuning(args)

    val config = BenchmarkConfig.parse(args)

    val registry = engineRegistry()
    val eb = registry[config.engine]
    if (eb == null) {
        printErr("Unknown engine: ${config.engine}")
        printErr("Available: ${registry.keys.joinToString(", ")}")
        kotlin.system.exitProcess(1)
    }

    if (config.showConfig) {
        print(config.display())
        return
    }

    // Install signal handlers before starting the server.
    signal(SIGTERM, staticCFunction { _ -> shutdownRequested.value = 1 })
    signal(SIGINT, staticCFunction { _ -> shutdownRequested.value = 1 })

    println("Starting benchmark server: ${config.summary()}")
    val stop = eb.start(config)

    // Wait for shutdown signal.
    while (shutdownRequested.value == 0) {
        sleep(1u)
    }

    // Exit immediately. The OS closes all file descriptors on process exit,
    // freeing the listen port. Calling engine.stop() first would be cleaner
    // but may block if internal coroutines don't terminate (known issue with
    // some Ktor engine implementations). For benchmark purposes, immediate
    // exit is sufficient.
    kotlin.system.exitProcess(0)
}

@OptIn(kotlin.native.runtime.NativeRuntimeApi::class)
private fun applyGcTuning(args: Array<String>) {
    for (arg in args) {
        if (arg.startsWith("--gc-target=")) {
            val value = arg.removePrefix("--gc-target=")
            val bytes = parseSizeBytes(value)
            kotlin.native.runtime.GC.targetHeapBytes = bytes
            println("GC targetHeapBytes=$bytes")
        }
        if (arg == "--gc-no-autotune") {
            kotlin.native.runtime.GC.autotune = false
            println("GC autotune=false")
        }
    }
}

private fun parseSizeBytes(s: String): Long {
    val lower = s.lowercase()
    return when {
        lower.endsWith("m") -> lower.dropLast(1).toLong() * 1024 * 1024
        lower.endsWith("g") -> lower.dropLast(1).toLong() * 1024 * 1024 * 1024
        lower.endsWith("k") -> lower.dropLast(1).toLong() * 1024
        else -> s.toLong()
    }
}
