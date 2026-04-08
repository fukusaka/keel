package io.github.fukusaka.keel.benchmark

/**
 * Kotlin/JS (Node.js) benchmark server entry point.
 *
 * Uses the shared [BenchmarkConfig] from commonMain with
 * [engineRegistry] for engine selection.
 *
 * Node.js maintains its event loop as long as there are active
 * handles (listeners, timers), so no explicit sleep/wait needed.
 *
 * Kotlin/JS production executables pass an empty array to `main`.
 * CLI arguments are read from `process.argv` directly, skipping
 * the first two entries (node binary and script path).
 */
fun main(@Suppress("UNUSED_PARAMETER") args: Array<String>) {
    val argv: Array<String> = js("process.argv.slice(2)") as Array<String>
    val config = BenchmarkConfig.parse(argv)
    validateTlsBackend(config)

    val registry = engineRegistry()
    val eb = registry[config.engine]
    if (eb == null) {
        printErr("Unknown engine: ${config.engine}")
        printErr("Available: ${registry.keys.joinToString(", ")}")
        benchmarkExit(1)
    }

    if (config.showConfig) {
        print(config.display())
        return
    }

    println("Starting benchmark server: ${config.summary()}")
    eb.start(config)

    // Node.js event loop keeps the process alive while the server
    // socket is open. No explicit blocking needed.
}
