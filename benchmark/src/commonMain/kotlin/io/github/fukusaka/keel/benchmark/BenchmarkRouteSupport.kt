package io.github.fukusaka.keel.benchmark

/**
 * Defaults and helpers shared by the `/sse-stream` route across every
 * benchmark engine adapter — the Ktor route block (`benchmarkModule`),
 * the raw keel pipeline routing handler, and the framework-specific
 * engines (`SpringEngine`, `VertxEngine`, `NettyRawEngine`). Centralising
 * them keeps a single source of truth so an engine's bench output isn't
 * off by a constant when the default size or frame count is tuned.
 */

/** Default SSE frame count for `/sse-stream` (override via `?count=N`). */
internal const val BENCHMARK_SSE_DEFAULT_COUNT = 100

/** Default SSE frame payload size in bytes for `/sse-stream` (override via `?size=M`). */
internal const val BENCHMARK_SSE_DEFAULT_SIZE = 1024

/**
 * Extracts an integer query parameter from a `key=value&...` query string.
 *
 * Accepts both leading-? and bare forms (e.g. `?count=10` or `count=10&size=1024`).
 * Returns `null` if the query is null/empty, the key is missing, or the value
 * is not an integer. Used by the raw-pipeline / Netty routing handlers; engines
 * that already expose a typed query API (Ktor, Spring WebFlux, Vert.x) keep
 * using their own.
 */
internal fun parseBenchmarkQueryInt(query: String?, name: String): Int? {
    if (query.isNullOrEmpty()) return null
    val q = if (query.startsWith("?")) query.substring(1) else query
    for (pair in q.splitToSequence('&')) {
        val eq = pair.indexOf('=')
        if (eq <= 0) continue
        if (pair.substring(0, eq) == name) {
            return pair.substring(eq + 1).toIntOrNull()
        }
    }
    return null
}
