package io.github.fukusaka.keel.benchmark

/**
 * Kotlin/Native benchmark server entry point.
 *
 * Uses the shared [BenchmarkConfig] from commonMain with platform-specific
 * [engineRegistry] for engine selection.
 *
 * Available engines (platform-dependent):
 * - keel-kqueue (macOS, default on macOS)
 * - keel-epoll (Linux, default on Linux)
 * - keel-nwconnection (macOS)
 * - ktor-cio (all platforms)
 */
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

    println("Starting benchmark server: ${config.summary()}")
    eb.start(config)
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
