package io.github.fukusaka.keel.codec.http

/**
 * HTTP/1.1 request head — request line + headers, without the body (RFC 7230 §3).
 *
 * Use [parseRequestHead] to obtain an instance from a [kotlinx.io.Source].
 * The body bytes remain in the source for streaming consumption.
 */
data class HttpRequestHead(
    val method: HttpMethod,
    val uri: String,
    val version: HttpVersion = HttpVersion.HTTP_1_1,
    val headers: HttpHeaders = HttpHeaders(),
) : HttpMessage {
    // Cached to avoid per-access String allocation on the hot path (RoutingHandler).
    // Fields outside the primary constructor do not participate in equals/hashCode/copy.
    // NONE — no synchronization needed; instances are confined to a single EventLoop thread.

    /** The path component of [uri], excluding query string and fragment. Cached on first access. */
    val path: String by lazy(LazyThreadSafetyMode.NONE) {
        uri.substringBefore('?').substringBefore('#')
    }

    /**
     * The query string component of [uri] (without leading '?'), or null if absent.
     * Cached on first access.
     *
     * Fragment identifier is excluded.
     */
    val queryString: String? by lazy(LazyThreadSafetyMode.NONE) {
        val idx = uri.indexOf('?')
        if (idx >= 0) uri.substring(idx + 1).substringBefore('#') else null
    }

    /**
     * Returns true if this request supports HTTP keep-alive.
     *
     * HTTP/1.1 connections are keep-alive by default (RFC 7230 §6.3).
     * Returns false only if `Connection: close` is explicitly set.
     * HTTP/1.0 connections are close by default; returns true only
     * if `Connection: keep-alive` is explicitly set.
     */
    val isKeepAlive: Boolean
        get() {
            val conn = headers.connection
            return when {
                conn?.contains("close", ignoreCase = true) == true -> false
                conn?.contains("keep-alive", ignoreCase = true) == true -> true
                else -> version == HttpVersion.HTTP_1_1
            }
        }
}
