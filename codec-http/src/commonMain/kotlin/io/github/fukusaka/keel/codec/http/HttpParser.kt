package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.io.BufferedSuspendSource
import kotlinx.io.Source
import kotlinx.io.readByteArray
import kotlinx.io.readLine

/**
 * HTTP/1.1 message parser (RFC 7230).
 *
 * Public API:
 *   parseRequest(source)  — consumes exactly one request from [source]
 *   parseResponse(source) — consumes exactly one response from [source]
 *
 * Each call consumes only one message; any remaining bytes stay in [source],
 * which naturally supports pipelining.
 */

/**
 * Parses a complete HTTP request (head + body) from [source].
 *
 * Use [parseRequestHead] when you want to defer body consumption
 * (e.g. streaming the body into a separate channel).
 */
fun parseRequest(source: Source): HttpRequest {
    val head = parseRequestHead(source)
    val body = readBody(source, head.headers)
    return HttpRequest(head.method, head.uri, head.version, head.headers, body)
}

/**
 * Parses a complete HTTP response (head + body) from [source].
 *
 * Use [parseResponseHead] when you want to defer body consumption.
 */
fun parseResponse(source: Source): HttpResponse {
    val head = parseResponseHead(source)
    val body = readBody(source, head.headers)
    return HttpResponse(head.status, head.version, head.headers, body)
}

/**
 * Parses only the request head (request line + headers) from [source].
 *
 * The body bytes remain in [source] for streaming consumption
 * based on Content-Length or Transfer-Encoding headers.
 */
fun parseRequestHead(source: Source): HttpRequestHead {
    val line = source.readLine() ?: throw HttpEofException("Unexpected EOF reading request line")
    val (method, uri, version) = parseRequestLine(line)
    val headers = parseHeaders(source)
    return HttpRequestHead(method, uri, version, headers)
}

/**
 * Parses only the response head (status line + headers) from [source].
 *
 * The body bytes remain in [source] for streaming consumption.
 */
fun parseResponseHead(source: Source): HttpResponseHead {
    val line = source.readLine() ?: throw HttpEofException("Unexpected EOF reading status line")
    val (version, status, _) = parseStatusLine(line)
    val headers = parseHeaders(source)
    return HttpResponseHead(status, version, headers)
}

// ---------------------------------------------------------------------------
// Suspend variants — use BufferedSuspendSource for zero-copy async I/O
// ---------------------------------------------------------------------------

/**
 * Suspend variant of [parseRequestHead] using [BufferedSuspendSource].
 *
 * Zero-copy: reads directly from IoBuf without kotlinx-io Buffer copy.
 * No runBlocking needed — suspends naturally on I/O wait.
 */
suspend fun parseRequestHead(source: BufferedSuspendSource): HttpRequestHead {
    val line = source.readLine()
        ?: throw HttpEofException("Unexpected EOF reading request line")
    val (method, uri, version) = parseRequestLine(line)
    val headers = parseHeaders(source)
    return HttpRequestHead(method, uri, version, headers)
}

/**
 * Suspend variant of [parseResponseHead] using [BufferedSuspendSource].
 */
suspend fun parseResponseHead(source: BufferedSuspendSource): HttpResponseHead {
    val line = source.readLine()
        ?: throw HttpEofException("Unexpected EOF reading status line")
    val (version, status, _) = parseStatusLine(line)
    val headers = parseHeaders(source)
    return HttpResponseHead(status, version, headers)
}

/**
 * Suspend variant of [parseHeaders] using [BufferedSuspendSource].
 */
internal suspend fun parseHeaders(source: BufferedSuspendSource): HttpHeaders {
    val headers = HttpHeaders()
    while (true) {
        val line = source.readLine() ?: break
        if (line.isEmpty()) break
        if (line[0] == ' ' || line[0] == '\t') {
            throw HttpParseException(
                "Obsolete line folding (obs-fold) is not allowed (RFC 7230 §3.2.6)"
            )
        }
        val colon = line.indexOf(':')
        if (colon < 1) throw HttpParseException("Invalid header field (missing ':'): $line")
        val name = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()
        headers.add(name, value)
    }
    return headers
}

// ---------------------------------------------------------------------------
// Internal helpers — exposed as `internal` for unit testing
// ---------------------------------------------------------------------------

internal data class RequestLine(val method: HttpMethod, val uri: String, val version: HttpVersion)
internal data class StatusLine(val version: HttpVersion, val status: HttpStatus, val reason: String)

/**
 * Parses "Method SP Request-Target SP HTTP-Version" (RFC 7230 §3.1.1).
 * SP must be a single space; the version token is case-sensitive.
 */
internal fun parseRequestLine(line: String): RequestLine {
    // indexOf-based parsing avoids List + extra String allocations from split().
    val sp1 = line.indexOf(' ')
    if (sp1 < 1) throw HttpParseException("Invalid request line (expected 3 tokens): $line")
    val sp2 = line.indexOf(' ', sp1 + 1)
    // Require exactly 2 spaces: second SP must exist and no third SP after it.
    if (sp2 < 0 || line.indexOf(' ', sp2 + 1) >= 0) {
        throw HttpParseException("Invalid request line (expected 3 tokens): $line")
    }
    return RequestLine(
        method  = HttpMethod.of(line.substring(0, sp1)),
        uri     = line.substring(sp1 + 1, sp2),
        version = HttpVersion.of(line.substring(sp2 + 1)),
    )
}

/**
 * Parses "HTTP-Version SP Status-Code SP Reason-Phrase" (RFC 7230 §3.1.2).
 * Reason-Phrase may be empty; clients MUST ignore it.
 */
internal fun parseStatusLine(line: String): StatusLine {
    val parts = line.split(" ", limit = 3)
    if (parts.size < 2) throw HttpParseException("Invalid status line (expected at least 2 tokens): $line")
    val code = parts[1].trim().toIntOrNull()?.takeIf { it in 100..999 }
        ?: throw HttpParseException("Invalid status code '${parts[1]}' in: $line")
    return StatusLine(
        version = HttpVersion.of(parts[0].trimEnd()),
        status  = HttpStatus(code),
        reason  = if (parts.size > 2) parts[2].trim() else "",
    )
}

/**
 * Reads header fields until the empty-line terminator (RFC 7230 §3.2).
 *
 * OWS (optional whitespace) is stripped from field-value (§3.2.6).
 * obs-fold (line folding: CRLF followed by SP/HTAB) is rejected per §3.2.6 MUST.
 */
internal fun parseHeaders(source: Source): HttpHeaders {
    val headers = HttpHeaders()
    while (true) {
        val line = source.readLine() ?: break
        if (line.isEmpty()) break
        // Detect obs-fold: a line that starts with SP or HTAB following a previous field
        if (line[0] == ' ' || line[0] == '\t') {
            throw HttpParseException(
                "Obsolete line folding (obs-fold) is not allowed (RFC 7230 §3.2.6)"
            )
        }
        val colon = line.indexOf(':')
        if (colon < 1) throw HttpParseException("Invalid header field (missing ':'): $line")
        val name  = line.substring(0, colon).trim()
        val value = line.substring(colon + 1).trim()   // strip OWS
        headers.add(name, value)
    }
    return headers
}

/**
 * Reads the message body, if any.
 *
 * Transfer-Encoding takes precedence over Content-Length (RFC 7230 §3.3.3),
 * which mitigates HTTP Request Smuggling when both headers are present.
 */
internal fun readBody(source: Source, headers: HttpHeaders): ByteArray? {
    if (headers.isChunked()) return readChunkedBody(source)
    val length = headers.contentLength() ?: return null
    return if (length == 0L) null else readBodyByContentLength(source, length)
}

/**
 * Reads exactly [length] bytes from [source] (RFC 7230 §3.3.2).
 */
internal fun readBodyByContentLength(source: Source, length: Long): ByteArray =
    source.readByteArray(length.toInt())

/**
 * Reads a chunked-transfer-encoded body (RFC 7230 §4.1).
 *
 * chunk-size is a hex integer; optional chunk-ext after ';' is ignored.
 * Trailer header fields after the last chunk are consumed and discarded.
 * Returns null for a zero-length body (last chunk only).
 */
internal fun readChunkedBody(source: Source): ByteArray? {
    val chunks = mutableListOf<ByteArray>()
    var total = 0
    while (true) {
        val sizeLine = source.readLine()
            ?: throw HttpEofException("Unexpected EOF reading chunk size")
        val sizeStr = sizeLine.substringBefore(';').trim()
        val chunkSize = sizeStr.toLongOrNull(16)
            ?: throw HttpParseException("Invalid chunk size '$sizeStr'")
        if (chunkSize == 0L) {
            // Consume optional trailer headers
            while (true) {
                val trailer = source.readLine() ?: break
                if (trailer.isEmpty()) break
            }
            break
        }
        val data = source.readByteArray(chunkSize.toInt())
        chunks += data
        total += data.size
        source.readLine()   // trailing CRLF after chunk data
    }
    if (chunks.isEmpty()) return null
    val result = ByteArray(total)
    var offset = 0
    for (chunk in chunks) { chunk.copyInto(result, offset); offset += chunk.size }
    return result
}
