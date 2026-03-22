package io.github.fukusaka.keel.codec.http

/**
 * HTTP/1.1 response message (RFC 7230 §3.1.2).
 *
 * Status-Line = HTTP-Version SP Status-Code SP Reason-Phrase CRLF
 *
 * The reason phrase is informational only; clients MUST ignore it (RFC 7230 §3.1.2).
 * [body] is null when no message body is present.
 */
class HttpResponse(
    val status: HttpStatus,
    val version: HttpVersion = HttpVersion.HTTP_1_1,
    val headers: HttpHeaders = HttpHeaders(),
    val body: ByteArray? = null,
)
