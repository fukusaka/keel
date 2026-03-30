package io.github.fukusaka.keel.benchmark

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix._exit
import platform.posix.signal

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

    // Install signal handlers that call _exit(0) directly. This is
    // async-signal-safe and guarantees immediate process termination.
    // The OS closes all file descriptors (including the listen socket),
    // freeing the port instantly. Using _exit() instead of exit() avoids
    // running atexit handlers and C++ destructors that may deadlock.
    println("Starting benchmark server: ${config.summary()}")
    eb.start(config)

    // Install signal handlers AFTER server start. Ktor/kotlinx-coroutines
    // overrides SIGTERM/SIGINT handlers during engine initialization
    // (verified via sigaction: handler address changes after start()).
    // _exit(0) is async-signal-safe and guarantees immediate termination.
    val handler = staticCFunction { _: Int -> _exit(0) }
    signal(SIGTERM, handler)
    signal(SIGINT, handler)

    // Block main thread. The signal handler terminates the process.
    while (true) {
        platform.posix.sleep(60u)
    }
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
