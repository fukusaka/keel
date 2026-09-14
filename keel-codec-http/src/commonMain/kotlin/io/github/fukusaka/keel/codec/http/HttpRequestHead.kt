package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.Releasable

/**
 * HTTP/1.1 request head — request line + headers, without the body (RFC 7230 §3).
 *
 * Use [parseRequestHead] to obtain an instance from a [kotlinx.io.Source].
 * The body bytes remain in the source for streaming consumption.
 *
 * **Buffer lifetime contract**: when this head comes from
 * [HttpRequestDecoder], its [headers] are borrowed from a pool and store
 * header values as zero-copy byte-range views over the recv buffer, which
 * they retain for the lifetime of the views. The **terminal consumer** of
 * the head (the handler that finishes the request) **must call
 * [release]**, or let a [io.github.fukusaka.keel.pipeline.TypedInboundHandler]
 * do it; failing to do so leaks one recv buffer per request. A head the
 * pipeline drops unconsumed is released by the pipeline. A value returned
 * by [HttpHeaders.get] (a `CharSequence` view) must not be retained past
 * that release — call `toString()` first, or keep a [detach]ed copy, for
 * anything that needs to outlive the request. Heads built directly (not via
 * the decoder) own no buffer and [release] does nothing.
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
) : HttpMessage, Releasable {

    // Which borrow of [headers] this message was built on. A body property, so
    // it is not part of equals / hashCode / copy's parameters; a copy reads the
    // same value from the same headers and the two share one release.
    private val headersLease: Int = headers.currentLease

    /**
     * Returns the pooled [headers] and the receive buffer their values view.
     *
     * Returns `true` on the call that gave them back and `false` on every
     * other: a second call, a call on a copy of a message already released,
     * a call made after the pool lent the same headers to another message,
     * and a call on a message whose headers were built directly and own
     * nothing. None of those throws, and none returns headers another
     * message is using.
     *
     * A [io.github.fukusaka.keel.pipeline.TypedInboundHandler] that takes
     * this type releases it when its callback returns; use [detach] to keep
     * the message beyond that.
     */
    override fun release(): Boolean = headers.releaseLease(headersLease)

    /**
     * Returns a copy that owns its headers, and releases this message.
     *
     * The copy's [headers] hold `String` values on the heap, so it stays
     * valid for as long as it is referenced and needs no release. This is
     * how a handler keeps a received message past the call that delivered
     * it. A message whose headers own nothing is returned as it is.
     *
     * @throws IllegalStateException if this message was already released.
     */
    fun detach(): HttpRequestHead {
        if (!headers.isPooled) return this
        check(headers.holdsLease(headersLease)) { "HttpRequestHead was already released" }
        val detached = copy(headers = headers.heapCopy())
        headers.releaseLease(headersLease)
        return detached
    }

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
