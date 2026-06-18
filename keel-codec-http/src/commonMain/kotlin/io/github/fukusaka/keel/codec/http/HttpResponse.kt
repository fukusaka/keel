package io.github.fukusaka.keel.codec.http

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
) {
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
            val headers = HttpHeaders.build {
                add(HttpHeaderName.CONTENT_TYPE, contentType)
                add(HttpHeaderName.CONTENT_LENGTH, (bytes?.size ?: 0).toString())
            }
            return HttpResponse(HttpStatus.OK, headers = headers, body = bytes)
        }

        /** Creates a 200 OK response with a binary body. */
        fun ok(body: ByteArray, contentType: String = "application/octet-stream"): HttpResponse {
            val headers = HttpHeaders.build {
                add(HttpHeaderName.CONTENT_TYPE, contentType)
                add(HttpHeaderName.CONTENT_LENGTH, body.size.toString())
            }
            return HttpResponse(HttpStatus.OK, headers = headers, body = body)
        }

        /** Creates a 404 Not Found response with an optional text body. */
        fun notFound(body: String? = null): HttpResponse {
            val bytes = body?.encodeToByteArray()
            val headers = HttpHeaders.build {
                add(HttpHeaderName.CONTENT_TYPE, "text/plain")
                add(HttpHeaderName.CONTENT_LENGTH, (bytes?.size ?: 0).toString())
            }
            return HttpResponse(HttpStatus.NOT_FOUND, headers = headers, body = bytes)
        }

        /** Creates a response with the given [status] and optional text body. */
        fun of(status: HttpStatus, body: String? = null, contentType: String = "text/plain"): HttpResponse {
            val bytes = body?.encodeToByteArray()
            val headers = HttpHeaders.build {
                add(HttpHeaderName.CONTENT_TYPE, contentType)
                add(HttpHeaderName.CONTENT_LENGTH, (bytes?.size ?: 0).toString())
            }
            return HttpResponse(status, headers = headers, body = bytes)
        }
    }
}
