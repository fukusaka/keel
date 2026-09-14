package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.Releasable

/**
 * HTTP/1.1 request message (RFC 7230 §3.1.1).
 *
 * Request-Line = Method SP Request-Target SP HTTP-Version CRLF
 *
 * [uri] holds the request-target as-is (origin-form, absolute-form,
 * authority-form, or asterisk-form). Full URI parsing is deferred to a later phase.
 * [body] is null when no message body is present.
 */
data class HttpRequest(
    val method: HttpMethod,
    val uri: String,
    val version: HttpVersion = HttpVersion.HTTP_1_1,
    val headers: HttpHeaders = HttpHeaders(),
    val body: ByteArray? = null,
) : Releasable {

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
    fun detach(): HttpRequest {
        if (!headers.isPooled) return this
        check(headers.holdsLease(headersLease)) { "HttpRequest was already released" }
        val detached = copy(headers = headers.heapCopy())
        headers.releaseLease(headersLease)
        return detached
    }

    // Eager-initialised for the same reason as HttpRequestHead: routing and
    // query-parameter parsing read both fields on every request, so the
    // `by lazy(NONE)` UnsafeLazyImpl holders were pure per-request overhead.

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
     * HTTP/1.0 connections are close by default.
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

    // ByteArray equality is reference-based by default in data classes.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpRequest) return false
        return method == other.method &&
            uri == other.uri &&
            version == other.version &&
            headers == other.headers &&
            body.contentEqualsNullable(other.body)
    }

    override fun hashCode(): Int {
        var result = method.hashCode()
        result = 31 * result + uri.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /** Creates a GET request with the given [uri] and optional [headers]. */
        fun get(uri: String, headers: HttpHeaders = HttpHeaders()): HttpRequest =
            HttpRequest(HttpMethod.GET, uri, headers = headers)

        /** Creates a POST request with the given [uri], optional [body] and [headers]. */
        fun post(uri: String, body: ByteArray? = null, headers: HttpHeaders = HttpHeaders()): HttpRequest =
            HttpRequest(HttpMethod.POST, uri, headers = headers, body = body)
    }
}

internal fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this === other -> true
    this == null || other == null -> false
    else -> contentEquals(other)
}
