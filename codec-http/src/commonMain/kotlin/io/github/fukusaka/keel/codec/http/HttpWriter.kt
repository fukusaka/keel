package io.github.fukusaka.keel.codec.http

import kotlinx.io.Sink
import kotlinx.io.write
import kotlinx.io.writeString

/**
 * HTTP/1.1 message writer (RFC 7230).
 *
 * Public API:
 *   writeRequest(request, sink)  — serialises an HttpRequest
 *   writeResponse(response, sink) — serialises an HttpResponse
 *
 * RFC conformance:
 * - Line endings are always CRLF (\r\n) as required by RFC 7230 §3.5.
 * - Header field names are written as-is (case-preserving, RFC 7230 §3.2).
 * - Content-Length is NOT added automatically; callers must set it in headers.
 * - Set-Cookie is written one field per line (no comma joining, RFC 6265).
 */

fun writeRequest(request: HttpRequest, sink: Sink) {
    writeRequestLine(request, sink)
    writeHeaders(request.headers, sink)
    request.body?.let { writeBodyWithContentLength(it, sink) }
}

fun writeResponse(response: HttpResponse, sink: Sink) {
    writeStatusLine(response, sink)
    writeHeaders(response.headers, sink)
    response.body?.let { writeBodyWithContentLength(it, sink) }
}

/**
 * Writes only the response head (status line + headers) to [sink].
 *
 * The body is not written; callers stream it separately.
 * The empty-line terminator after the last header is included.
 */
fun writeResponseHead(
    status: HttpStatus,
    version: HttpVersion,
    headers: HttpHeaders,
    sink: Sink,
) {
    sink.writeString("${version.text} ${status.code} ${status.reasonPhrase()}\r\n")
    writeHeaders(headers, sink)
}

// ---------------------------------------------------------------------------
// Internal helpers — exposed as `internal` for unit testing
// ---------------------------------------------------------------------------

/** Writes "Method SP Request-Target SP HTTP-Version CRLF" (RFC 7230 §3.1.1). */
internal fun writeRequestLine(request: HttpRequest, sink: Sink) {
    sink.writeString("${request.method} ${request.uri} ${request.version.text}\r\n")
}

/**
 * Writes "HTTP-Version SP Status-Code SP Reason-Phrase CRLF" (RFC 7230 §3.1.2).
 * Reason-Phrase is derived from the status code; an empty string is written for unknown codes.
 */
internal fun writeStatusLine(response: HttpResponse, sink: Sink) {
    sink.writeString("${response.version.text} ${response.status.code} ${response.status.reasonPhrase()}\r\n")
}

/**
 * Writes all header fields followed by the empty-line terminator (RFC 7230 §3.2).
 * Each field is written as "field-name: field-value CRLF", one line per value.
 */
internal fun writeHeaders(headers: HttpHeaders, sink: Sink) {
    headers.forEach { name, value -> sink.writeString("$name: $value\r\n") }
    sink.writeString("\r\n")
}

/** Writes [body] bytes to [sink] as-is (used when Content-Length is already set in headers). */
internal fun writeBodyWithContentLength(body: ByteArray, sink: Sink) {
    if (body.isNotEmpty()) sink.write(body)
}

/**
 * Writes [body] using chunked transfer encoding (RFC 7230 §4.1).
 * Chunk sizes are written in lowercase hexadecimal.
 * Callers must set "Transfer-Encoding: chunked" in the headers before calling this.
 */
internal fun writeChunkedBody(body: ByteArray, sink: Sink, chunkSize: Int = 4096) {
    var offset = 0
    while (offset < body.size) {
        val end = minOf(offset + chunkSize, body.size)
        val length = end - offset
        sink.writeString("${length.toString(16)}\r\n")
        sink.write(body, offset, end)
        sink.writeString("\r\n")
        offset = end
    }
    sink.writeString("0\r\n\r\n")
}
