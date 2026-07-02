package io.github.fukusaka.keel.codec.http

/**
 * HTTP/1.1 request head — request line + headers, without the body (RFC 7230 §3).
 *
 * Use [parseRequestHead] to obtain an instance from a [kotlinx.io.Source].
 * The body bytes remain in the source for streaming consumption.
 *
 * **Buffer lifetime contract**: when this head comes from
 * [HttpRequestDecoder], its [headers] store header values as zero-copy
 * byte-range views over the recv buffer, and [HttpHeaders] retains that
 * buffer for the lifetime of the views. The **terminal consumer** of the
 * head (the handler that finishes the request) **must call
 * `headers.release()`** to free the retained buffer; failing to do so
 * leaks one recv buffer per request. A value returned by
 * [HttpHeaders.get] (a `CharSequence` view) must not be retained past
 * that `release()` — call `toString()` first to copy out anything that
 * needs to outlive the request. Heads built directly (not via the
 * decoder) own no buffer and `release()` is a no-op.
 */
data class HttpRequestHead(
    val method: HttpMethod,
    // Deliberately a String, not a CharSequence view over the recv buffer
    // (considered 2026-07-03, rejected): the net saving is one small String
    // per request (a view object costs nearly as much), while a view would
    // break String equality symmetry (`"x" == uri` never matches a view),
    // break this data class's equals, and extend the headers
    // release-lifecycle contract to the most-touched request field. The
    // String is the same deliberate application-API boundary as
    // WsMessage's ByteArray payloads.
    val uri: String,
    val version: HttpVersion = HttpVersion.HTTP_1_1,
    val headers: HttpHeaders = HttpHeaders(),
) : HttpMessage {
    // Eager-initialised so each request avoids the per-instance UnsafeLazyImpl
    // allocations the `by lazy(NONE)` form required. Both fields are read on
    // every request by the routing handler (`path`) and the server's query-
    // parameter parser (`queryString`), so lazy caching never won — only the
    // holders themselves dominated the alloc cost (JFR /hello @ 450K req/s:
    // ~12% of allocation pressure was UnsafeLazyImpl). Eager initialisers in
    // the class body (not the primary constructor) keep them out of
    // equals/hashCode/copy.

    /** The path component of [uri], excluding query string and fragment. */
    val path: String = uri.substringBefore('?').substringBefore('#')

    /**
     * The query string component of [uri] (without leading '?'), or null if absent.
     *
     * Fragment identifier is excluded.
     */
    val queryString: String? = run {
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
