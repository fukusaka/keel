package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.Releasable

/**
 * HTTP/1.1 response message (RFC 7230 §3.1.2).
 *
 * Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF
 *
 * The reason phrase is informational only; clients MUST ignore it (RFC 7230 §3.1.2).
 * [body] is null when no message body is present.
 *
 * **Atomic refcount audit (pluggability item 8, 2026-06-18)**: the
 * pluggability series originally framed shared [HttpResponse] constants
 * built through [of] / [ok] / [notFound] (e.g. the `NOT_FOUND_RESPONSE`
 * / `INTERNAL_ERROR_RESPONSE` / `BAD_REQUEST_RESPONSE` singletons in
 * `HttpServerHandler`) as a possible source of atomic CAS contention
 * after the unified atomic refcount in `AbstractIoBuf`. Re-examining
 * the emit path shows no such seam exists: [body] is `ByteArray?`,
 * which has no refcount, and `HttpResponseEncoder.encode` allocates a
 * fresh `IoBuf` per request via `allocator.allocate(size)` and copies
 * the shared body bytes through `buf.writeByteArray`. Each emission
 * therefore starts with `refCount = 1` owned by the encoder, transfers
 * ownership to the transport, and releases after the write — no shared
 * `IoBuf` is ever produced. The large-body fast path
 * (`tryWrapBytes`, threshold 8 KiB) only fires for bodies above the
 * threshold; the shared error constants ("Not Found" / "Internal
 * Server Error" / "Bad Request") are well below it and never reach
 * that branch. Even if they did, `tryWrapBytes` returns a fresh
 * `IoBuf` wrapper around the shared `ByteArray` rather than handing
 * out a shared `IoBuf`. Conclusion: shared [HttpResponse] constants do
 * not trigger contended atomic CAS on the refcount, and the originally
 * scoped microbenchmark is not needed at the current API shape. A
 * future shift to `body: IoBuf` or a pooled / shared `IoBuf` body
 * representation would change this calculus and warrant a fresh
 * audit at that point.
 */
data class HttpResponse(
    val status: HttpStatus,
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
    fun detach(): HttpResponse {
        if (!headers.isPooled) return this
        check(headers.holdsLease(headersLease)) { "HttpResponse was already released" }
        val detached = copy(headers = headers.heapCopy())
        headers.releaseLease(headersLease)
        return detached
    }

    /**
     * Returns true if this response's connection can be kept alive.
     *
     * HTTP/1.1 connections are keep-alive by default (RFC 7230 §6.3);
     * returns false only if `Connection: close` is explicitly set.
     * HTTP/1.0 connections are close by default; returns true only if
     * `Connection: keep-alive` is explicitly set. Mirrors
     * [HttpRequestHead.isKeepAlive] for the response side, so a client can
     * decide whether to reuse the connection.
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
        if (other !is HttpResponse) return false
        return status == other.status &&
            version == other.version &&
            headers == other.headers &&
            body.contentEqualsNullable(other.body)
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + (body?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        /** Creates a 200 OK response with an optional text body. */
        fun ok(body: String? = null, contentType: String = "text/plain"): HttpResponse {
            val bytes = body?.encodeToByteArray()
            val headers = contentHeaders(contentType, bytes?.size ?: 0)
            return HttpResponse(HttpStatus.OK, headers = headers, body = bytes)
        }

        /** Creates a 200 OK response with a binary body. */
        fun ok(body: ByteArray, contentType: String = "application/octet-stream"): HttpResponse {
            val headers = contentHeaders(contentType, body.size)
            return HttpResponse(HttpStatus.OK, headers = headers, body = body)
        }

        /** Creates a 404 Not Found response with an optional text body. */
        fun notFound(body: String? = null): HttpResponse {
            val bytes = body?.encodeToByteArray()
            val headers = contentHeaders("text/plain", bytes?.size ?: 0)
            return HttpResponse(HttpStatus.NOT_FOUND, headers = headers, body = bytes)
        }

        /** Creates a response with the given [status] and optional text body. */
        fun of(status: HttpStatus, body: String? = null, contentType: String = "text/plain"): HttpResponse {
            val bytes = body?.encodeToByteArray()
            val headers = contentHeaders(contentType, bytes?.size ?: 0)
            return HttpResponse(status, headers = headers, body = bytes)
        }

        /**
         * Builds the `Content-Type` + `Content-Length` header pair every
         * factory returns. Constructs the [HttpHeaders] directly rather than
         * through [HttpHeaders.build] so no per-call builder lambda is
         * allocated — on Kotlin/Native (where escape analysis does not elide
         * a lambda passed to a non-inline function, unlike the JVM JIT) the
         * closure was ~18-20 bytes on every response. Pre-sizes to the two
         * fields it adds.
         */
        private fun contentHeaders(contentType: String, contentLength: Int): HttpHeaders {
            val headers = HttpHeaders()
            headers.reserve(RESPONSE_HEADER_COUNT)
            headers.add(HttpHeaderName.CONTENT_TYPE, contentType)
            headers.add(HttpHeaderName.CONTENT_LENGTH, contentLength.toString())
            return headers
        }

        /** Header-field count [contentHeaders] adds (`Content-Type` + `Content-Length`). */
        private const val RESPONSE_HEADER_COUNT: Int = 2
    }
}
