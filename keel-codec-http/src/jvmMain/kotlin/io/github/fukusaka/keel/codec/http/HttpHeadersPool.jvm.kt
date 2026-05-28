package io.github.fukusaka.keel.codec.http

/**
 * Per-thread pool stack backed by [java.lang.ThreadLocal]. Each
 * EventLoop worker thread lazily gets its own [ArrayDeque] on first
 * access, so [HttpHeadersPool] borrow / release never touch a shared
 * structure across threads.
 */
private val threadLocalStack: ThreadLocal<ArrayDeque<HttpHeaders>> =
    ThreadLocal.withInitial { ArrayDeque() }

internal actual fun headersPoolStack(): ArrayDeque<HttpHeaders> = threadLocalStack.get()

internal actual fun readBypassEnvVar(): Boolean =
    System.getenv("KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS") == "1"
