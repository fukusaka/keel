package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpStatus

/**
 * The response of a [KeelHttpClient] request, fully materialised into
 * plain, GC-owned values.
 *
 * The client decodes the response through the zero-copy
 * `:keel-codec-http` client codec — whose header views alias the pooled
 * receive buffer — then copies the fields out and releases the pooled
 * buffers before the connection closes. By the time a [KeelHttpResponse]
 * reaches the caller nothing here references a pooled buffer: [headers]
 * hold `String` values and [body] is an owned `ByteArray`, so there is
 * nothing to release.
 *
 * @property status the response status line.
 * @property headers the response headers (materialised, `String` values).
 * @property body the aggregated response body; empty for a bodyless
 *   response (e.g. `204`, or a `HEAD` request).
 */
public class KeelHttpResponse internal constructor(
    public val status: HttpStatus,
    public val headers: HttpHeaders,
    public val body: ByteArray,
) {
    /** The response body decoded as UTF-8 text. */
    public fun bodyText(): String = body.decodeToString()

    /**
     * The first value of header [name] (case-insensitive), or null if the
     * response carries no such header.
     */
    public operator fun get(name: String): String? = headers[name]?.toString()
}
