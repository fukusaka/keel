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
) {
    /**
     * Returns true if this request supports HTTP keep-alive.
     *
     * HTTP/1.1 connections are keep-alive by default (RFC 7230 §6.3).
     * Returns false only if `Connection: close` is explicitly set.
     * HTTP/1.0 connections are close by default; returns true only
     * if `Connection: keep-alive` is explicitly set.
     */
    fun isKeepAlive(): Boolean {
        val connection = headers["Connection"]
        return when {
            connection.equals("close", ignoreCase = true) -> false
            version == HttpVersion.HTTP_1_1 -> true
            connection.equals("keep-alive", ignoreCase = true) -> true
            else -> false
        }
    }
}
