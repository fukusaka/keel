package io.github.fukusaka.keel.codec.http

/**
 * HTTP/1.1 response message (RFC 7230 §3.1.2).
 *
 * Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF
 *
 * The reason phrase is informational only; clients MUST ignore it (RFC 7230 §3.1.2).
 * [body] is null when no message body is present.
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