package io.github.fukusaka.keel.codec.http

/**
 * JS is single-threaded (one Node.js event loop), so no thread-local
 * machinery is needed — a plain process-wide singleton stack is already
 * confined to the only thread that exists.
 */
private val jsStack: ArrayDeque<HttpHeaders> = ArrayDeque()

internal actual fun headersPoolStack(): ArrayDeque<HttpHeaders> = jsStack

// Node.js `process.env.KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS === "1"`.
// The `js("…")` invocation is the documented way to bridge to the
// Node runtime's `process` global from Kotlin/JS without pulling in
// a node typings dependency.
@Suppress("UnsafeCastFromDynamic")
internal actual fun readBypassEnvVar(): Boolean =
    js("(typeof process !== 'undefined') && process.env && process.env.KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS === '1'")
