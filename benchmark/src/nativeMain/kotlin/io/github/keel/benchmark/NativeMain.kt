package io.github.keel.benchmark

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
