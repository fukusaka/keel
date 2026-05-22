package io.github.fukusaka.keel.codec.http

/**
 * JS is single-threaded (one Node.js event loop), so no thread-local
 * machinery is needed — a plain process-wide singleton stack is already
 * confined to the only thread that exists.
 */
private val jsStack: ArrayDeque<HttpHeaders> = ArrayDeque()

internal actual fun headersPoolStack(): ArrayDeque<HttpHeaders> = jsStack
