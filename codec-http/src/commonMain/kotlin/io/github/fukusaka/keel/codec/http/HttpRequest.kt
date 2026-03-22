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
class HttpRequest(
    val method: HttpMethod,
    val uri: String,
    val version: HttpVersion = HttpVersion.HTTP_1_1,
    val headers: HttpHeaders = HttpHeaders(),
    val body: ByteArray? = null,
)
