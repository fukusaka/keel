package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpStatus

/** CRLF line terminator separating HTTP/1.1 status line and header fields. */
private const val CRLF = "\r\n"

/** Radix of the hex chunk-size prefix in `chunked` transfer-encoding. */
private const val HEX_RADIX = 16

/**
 * Parses a raw HTTP/1.1 response — the bytes [KeelHttpTestClient] read off
 * the in-memory loopback — into a [TestHttpResponse].
 *
 * Handles the status line, the header block, and a body framed either by
 * `Content-Length` or by `chunked` transfer-encoding (both of which the
 * keel-server-http response paths emit). A response with neither framing
 * header — `204`, `304`, a `HEAD` response — is treated as bodyless.
 *
 * **Interim implementation.** This is a deliberately minimal hand-rolled
 * HTTP/1.1 response decoder: keel-codec-http currently ships only the
 * server-side codec (`HttpRequestDecoder` / `HttpResponseEncoder`), so
 * there is no client-side `HttpResponseDecoder` to reuse. When
 * keel-codec-http gains a client-side codec (Phase 12 — the keel HTTP
 * client), [KeelHttpTestClient] should install it on the loopback channel
 * and read a typed response instead, retiring this parser.
 *
 * @throws IllegalStateException if [raw] is not a well-formed response.
 */
internal fun parseHttpResponse(raw: ByteArray): TestHttpResponse {
    val headerEnd = indexOfHeaderEnd(raw)
    check(headerEnd >= 0) { "malformed response: no header terminator (CRLFCRLF)" }
    val headerText = raw.decodeToString(0, headerEnd)
    val lines = headerText.split(CRLF)
    check(lines.isNotEmpty()) { "malformed response: empty head" }

    val status = parseStatusLine(lines[0])
    val headers = parseHeaders(lines.drop(1))
    val bodyStart = headerEnd + 4
    val body = parseBody(raw, bodyStart, headers)
    return TestHttpResponse(status, headers, body)
}

/** The `CRLFCRLF` head/body separator as a byte sequence. */
private val HEADER_TERMINATOR = byteArrayOf(CR, LF, CR, LF)

/** Index of the first byte of the `CRLFCRLF` head/body separator, or -1. */
private fun indexOfHeaderEnd(raw: ByteArray): Int {
    outer@ for (i in 0..raw.size - HEADER_TERMINATOR.size) {
        for (j in HEADER_TERMINATOR.indices) {
            if (raw[i + j] != HEADER_TERMINATOR[j]) continue@outer
        }
        return i
    }
    return -1
}

/** Parses `HTTP/1.1 <code> <reason>` into its [HttpStatus]. */
private fun parseStatusLine(line: String): HttpStatus {
    val parts = line.split(' ', limit = 3)
    check(parts.size >= 2 && parts[0].startsWith("HTTP/")) { "malformed status line: '$line'" }
    val code = parts[1].toIntOrNull() ?: error("malformed status code in '$line'")
    return HttpStatus(code)
}

/** Parses `Name: value` header lines into an [HttpHeaders]. */
private fun parseHeaders(lines: List<String>): HttpHeaders =
    HttpHeaders.build {
        for (line in lines) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            check(colon > 0) { "malformed header line: '$line'" }
            add(line.substring(0, colon).trim(), line.substring(colon + 1).trim())
        }
    }

/**
 * Extracts the body from [raw] starting at [bodyStart], framed by the
 * `Content-Length` or `chunked` `Transfer-Encoding` of [headers].
 */
private fun parseBody(raw: ByteArray, bodyStart: Int, headers: HttpHeaders): ByteArray {
    val transferEncoding = headers[HttpHeaderName.TRANSFER_ENCODING]
    if (transferEncoding != null && transferEncoding.contains("chunked", ignoreCase = true)) {
        return decodeChunked(raw, bodyStart)
    }
    val contentLength = headers[HttpHeaderName.CONTENT_LENGTH]?.toIntOrNull()
    if (contentLength != null) {
        val end = (bodyStart + contentLength).coerceAtMost(raw.size)
        return raw.copyOfRange(bodyStart.coerceAtMost(raw.size), end)
    }
    // No framing header: a bodyless response (204 / 304 / HEAD).
    return ByteArray(0)
}

/**
 * Decodes a `chunked` transfer-encoded body: a sequence of
 * `<hex-size>CRLF<data>CRLF` chunks terminated by a zero-size chunk.
 */
private fun decodeChunked(raw: ByteArray, bodyStart: Int): ByteArray {
    val out = ArrayList<Byte>()
    var pos = bodyStart
    while (pos < raw.size) {
        val lineEnd = indexOfCrlf(raw, pos)
        check(lineEnd >= 0) { "malformed chunked body: no CRLF after chunk size" }
        val sizeToken = raw.decodeToString(pos, lineEnd).substringBefore(';').trim()
        val size = sizeToken.toIntOrNull(HEX_RADIX)
            ?: error("malformed chunk size: '$sizeToken'")
        pos = lineEnd + 2
        if (size == 0) break
        val end = pos + size
        check(end <= raw.size) { "truncated chunked body" }
        for (i in pos until end) out.add(raw[i])
        // Skip the trailing CRLF after the chunk data.
        pos = end + 2
    }
    return out.toByteArray()
}

/** Index of the next `CRLF` at or after [from], or -1. */
private fun indexOfCrlf(raw: ByteArray, from: Int): Int {
    for (i in from..raw.size - 2) {
        if (raw[i] == CR && raw[i + 1] == LF) return i
    }
    return -1
}

/** ASCII carriage return. */
private const val CR: Byte = 13

/** ASCII line feed. */
private const val LF: Byte = 10
