package io.github.fukusaka.keel.benchmark

/** JS default engine — pipeline-http-nodejs (Node.js pipeline). */
actual fun defaultEngine(): String = "pipeline-http-nodejs"

/** JS engine registry: Node.js pipeline HTTP variant. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "pipeline-http-nodejs" to PipelineHttpNodejsBenchmark,
)

/** Print to stderr via [console.error]. */
actual fun printErr(message: String) {
    console.error(message)
}

/** Terminate the Node.js process via `process.exit()`. */
actual fun benchmarkExit(code: Int): Nothing {
    js("process.exit(code)")
    // Unreachable — process.exit terminates immediately.
    throw IllegalStateException("process.exit did not terminate")
}

/** Read [name] from the Node.js environment via `process.env`; return `null` for unset / empty. */
actual fun getEnvVar(name: String): String? {
    // `?? null` is unsupported by the Kotlin/JS IR backend; fall back to
    // a `typeof === "string"` check that returns `null` otherwise.
    val raw: dynamic = js("(typeof process !== 'undefined' && typeof process.env[name] === 'string') ? process.env[name] : null")
    val value: String? = raw as String?
    return value?.takeIf { it.isNotEmpty() }
}

/** Number of available CPU cores — not exposed in Kotlin/JS; returns 1. */
actual fun availableProcessors(): Int = 1

/** Dispatchers.IO parallelism — not applicable on JS; returns 1. */
actual fun ioParallelism(): Int = 1

/**
 * Detect OS socket defaults — not available in Node.js.
 *
 * Returns estimated fallback values since Node.js does not expose
 * low-level socket options via a synchronous API.
 */
actual fun detectOsSocketDefaults(): OsSocketDefaults = OsSocketDefaults(
    tcpNoDelay = false,
    reuseAddress = false,
    backlog = 511, // Node.js default backlog
    sendBuffer = 131072,
    receiveBuffer = 131072,
    fallback = true,
)
