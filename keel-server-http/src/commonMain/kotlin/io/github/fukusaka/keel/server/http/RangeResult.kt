package io.github.fukusaka.keel.server.http

/** The `bytes` unit token — the only range unit keel parses (RFC 9110 §14.1). */
private const val BYTES_UNIT_PREFIX = "bytes="

/**
 * Outcome of parsing a `Range` request header against an asset of a known
 * size (RFC 9110 §14.2).
 *
 * The serve layer maps each case to a response: [FullResponse] keeps the
 * `200 OK` path, [Satisfiable] produces a `206 Partial Content`, and
 * [Unsatisfiable] produces a `416 Range Not Satisfiable`.
 */
internal sealed interface RangeResult {

    /**
     * The header is absent, uses a non-`bytes` unit, lists multiple
     * ranges, or is syntactically invalid. A syntactically invalid
     * `Range` is ignored per RFC 9110 §14.2, so the asset is served whole
     * with `200 OK`.
     */
    data object FullResponse : RangeResult

    /**
     * A single satisfiable range. [start] and [end] are absolute,
     * zero-based, and inclusive; the served length is `end - start + 1`.
     */
    data class Satisfiable(val start: Long, val end: Long) : RangeResult

    /**
     * A single syntactically valid range that cannot be satisfied for an
     * asset of the parsed size (the first byte lies at or past the end,
     * or a zero-length suffix was requested). The serve layer answers
     * `416` with `Content-Range: bytes * /assetSize`.
     */
    data object Unsatisfiable : RangeResult
}

/**
 * Parses a single-range `Range` request header against [assetSize].
 *
 * Only the `bytes=` unit is handled; any other unit yields
 * [RangeResult.FullResponse]. The three single-range forms are
 * `START-END` (inclusive), `START-` (START to end), and `-SUFFIXLEN`
 * (the last SUFFIXLEN bytes). A comma — i.e. a multi-range request — also
 * yields [RangeResult.FullResponse] because multipart/byteranges is out
 * of scope.
 *
 * @param headerValue the raw `Range` header field value.
 * @param assetSize the asset size in bytes; ranges resolve against it.
 */
internal fun parseByteRange(headerValue: String, assetSize: Long): RangeResult {
    val trimmed = headerValue.trim()
    if (!trimmed.startsWith(BYTES_UNIT_PREFIX, ignoreCase = true)) return RangeResult.FullResponse
    val spec = trimmed.substring(BYTES_UNIT_PREFIX.length).trim()
    // A comma marks a multi-range request — out of scope, serve the whole asset.
    if (spec.isEmpty() || spec.contains(',')) return RangeResult.FullResponse

    val dash = spec.indexOf('-')
    if (dash < 0) return RangeResult.FullResponse
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()

    return if (startText.isEmpty()) {
        parseSuffixRange(endText, assetSize)
    } else {
        parseNormalRange(startText, endText, assetSize)
    }
}

/** Parses a `-SUFFIXLEN` suffix range — the last SUFFIXLEN bytes. */
private fun parseSuffixRange(suffixText: String, assetSize: Long): RangeResult {
    val suffixLength = suffixText.toLongOrNull() ?: return RangeResult.FullResponse
    if (suffixLength < 0) return RangeResult.FullResponse
    // A zero-length suffix cannot identify any byte — unsatisfiable.
    if (suffixLength == 0L) return RangeResult.Unsatisfiable
    // A zero-length asset cannot satisfy any range.
    if (assetSize == 0L) return RangeResult.Unsatisfiable
    // A suffix at least as long as the asset selects the whole asset.
    val start = if (suffixLength >= assetSize) 0L else assetSize - suffixLength
    return RangeResult.Satisfiable(start, assetSize - 1)
}

/** Parses a `START-END` or `START-` range; [endText] empty means "to the end". */
private fun parseNormalRange(startText: String, endText: String, assetSize: Long): RangeResult {
    val start = startText.toLongOrNull() ?: return RangeResult.FullResponse
    if (start < 0) return RangeResult.FullResponse
    // First byte at or past the end is unsatisfiable.
    if (start >= assetSize) return RangeResult.Unsatisfiable

    val end = if (endText.isEmpty()) {
        assetSize - 1
    } else {
        val parsed = endText.toLongOrNull() ?: return RangeResult.FullResponse
        if (parsed < 0) return RangeResult.FullResponse
        // A syntactically invalid range (start past end) is ignored — serve whole.
        if (parsed < start) return RangeResult.FullResponse
        // Clamp an end past the last byte to the last byte.
        if (parsed >= assetSize) assetSize - 1 else parsed
    }
    return RangeResult.Satisfiable(start, end)
}
