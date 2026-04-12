package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.EmptyIoBuf
import io.github.fukusaka.keel.buf.IoBuf

/**
 * Terminal marker for a streaming HTTP message body.
 *
 * Carries an optional final body chunk (often zero bytes) and
 * RFC 7230 §4.1.2 trailer headers from chunked transfer-encoding.
 *
 * Use [EMPTY] for zero-byte + no-trailer termination to avoid
 * allocating a fresh instance per request — this is the common case
 * for GET requests and Content-Length bodies with no trailers.
 */
class HttpBodyEnd(
    content: IoBuf,
    val trailers: HttpHeaders = HttpHeaders.EMPTY,
) : HttpBody(content) {

    override fun toString(): String =
        "HttpBodyEnd(${content.readableBytes} bytes, trailers=${trailers.size})"

    companion object {
        /** Zero-payload, trailer-less terminator singleton. */
        val EMPTY: HttpBodyEnd = HttpBodyEnd(EmptyIoBuf, HttpHeaders.EMPTY)
    }
}
