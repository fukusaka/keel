package io.github.fukusaka.keel.codec.http

/**
 * HTTP/1.1 response head — status line + headers, without the body (RFC 7230 §3).
 *
 * Use [parseResponseHead] to obtain an instance from a [kotlinx.io.Source].
 * The body bytes remain in the source for streaming consumption.
 */
data class HttpResponseHead(
    val status: HttpStatus,
    val version: HttpVersion = HttpVersion.HTTP_1_1,
    val headers: HttpHeaders = HttpHeaders(),
) : HttpMessage
