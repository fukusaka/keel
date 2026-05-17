package io.github.fukusaka.keel.testing.server.http

/** CRLF as a byte pair. */
private const val CR: Byte = 13
private const val LF: Byte = 10

/** Radix of the hex chunk-size prefix in `chunked` transfer-encoding. */
private const val HEX_RADIX = 16

/**
 * Reports whether [accumulated] already holds a complete HTTP/1.1
 * response, so [KeelHttpTestClient] can stop reading off the channel
 * without waiting for the (keep-alive) connection to close.
 *
 * Completeness is decided by the framing the keel-server-http response
 * paths emit:
 *
 * - the header block must be terminated by `CRLFCRLF`;
 * - a `Content-Length` response is complete once that many body bytes
 *   have arrived;
 * - a `chunked` response is complete once the zero-size terminator chunk
 *   has arrived;
 * - a response with neither framing header — `204`, `304`, or any
 *   response to a `HEAD` request — is complete as soon as the header
 *   block is.
 *
 * @param isHead `true` when the request method was `HEAD`; the response
 *   is then bodyless even if it carries a `Content-Length`.
 */
internal fun isResponseComplete(accumulated: List<Byte>, isHead: Boolean): Boolean {
    val headerEnd = indexOfHeaderEnd(accumulated)
    if (headerEnd < 0) return false
    val bodyStart = headerEnd + 4
    if (isHead) return true

    val headerText = decode(accumulated, 0, headerEnd)
    val headers = parseHeaderLines(headerText)

    val te = headers["transfer-encoding"]
    if (te != null && te.contains("chunked", ignoreCase = true)) {
        return chunkedBodyComplete(accumulated, bodyStart)
    }
    val contentLength = headers["content-length"]?.trim()?.toIntOrNull()
    if (contentLength != null) {
        return accumulated.size - bodyStart >= contentLength
    }
    // No framing header: a bodyless response (204 / 304).
    return true
}

/** Decodes [list]'s `[from, to)` byte range as UTF-8. */
private fun decode(list: List<Byte>, from: Int, to: Int): String {
    val bytes = ByteArray(to - from)
    for (i in bytes.indices) bytes[i] = list[from + i]
    return bytes.decodeToString()
}

/** Index of the first byte of the `CRLFCRLF` head/body separator, or -1. */
private fun indexOfHeaderEnd(list: List<Byte>): Int {
    for (i in 0..list.size - 4) {
        if (isCrlfCrlf(list, i)) return i
    }
    return -1
}

/** Whether the four bytes of [list] at [i] are `CRLFCRLF`. */
private fun isCrlfCrlf(list: List<Byte>, i: Int): Boolean {
    if (list[i] != CR || list[i + 1] != LF) return false
    return list[i + 2] == CR && list[i + 3] == LF
}

/** Parses `Name: value` lines into a lowercase-keyed map. */
private fun parseHeaderLines(headerText: String): Map<String, String> {
    val out = HashMap<String, String>()
    val lines = headerText.split("\r\n")
    for (i in 1 until lines.size) {
        val line = lines[i]
        if (line.isEmpty()) continue
        val colon = line.indexOf(':')
        if (colon <= 0) continue
        out[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
    }
    return out
}

/**
 * Reports whether a `chunked` body starting at [bodyStart] has received
 * its zero-size terminator chunk.
 */
private fun chunkedBodyComplete(list: List<Byte>, bodyStart: Int): Boolean {
    var pos = bodyStart
    while (pos < list.size) {
        val lineEnd = indexOfCrlf(list, pos)
        if (lineEnd < 0) return false
        val sizeToken = decode(list, pos, lineEnd).substringBefore(';').trim()
        val size = sizeToken.toIntOrNull(HEX_RADIX) ?: return false
        pos = lineEnd + 2
        if (size == 0) return true
        // Skip chunk data plus its trailing CRLF.
        pos += size + 2
    }
    return false
}

/** Index of the next `CRLF` at or after [from], or -1. */
private fun indexOfCrlf(list: List<Byte>, from: Int): Int {
    for (i in from..list.size - 2) {
        if (list[i] == CR && list[i + 1] == LF) return i
    }
    return -1
}
