package io.github.fukusaka.keel.codec.http

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
) {
    // Cached to avoid per-access String allocation (same rationale as HttpRequestHead).
    private var _path: String? = null
    private var _queryString: String? = UNSET_QUERY

    /** The path component of [uri], excluding query string and fragment. Cached on first access. */
    val path: String
        get() {
            _path?.let { return it }
            return uri.substringBefore('?').substringBefore('#').also { _path = it }
        }

    /**
     * The query string component of [uri] (without leading '?'), or null if absent.
     * Cached on first access.
     *
     * Fragment identifier is excluded.
     */
    val queryString: String?
        get() {
            if (_queryString !== UNSET_QUERY) return _queryString
            val idx = uri.indexOf('?')
            val qs = if (idx >= 0) uri.substring(idx + 1).substringBefore('#') else null
            _queryString = qs
            return qs
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

/** Sentinel for distinguishing "not yet computed" from "computed as null". */
private val UNSET_QUERY: String? = charArrayOf('\u0000').concatToString()

internal fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this === other -> true
    this == null || other == null -> false
    else -> contentEquals(other)
}
