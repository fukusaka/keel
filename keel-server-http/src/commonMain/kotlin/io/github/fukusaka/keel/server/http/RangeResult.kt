package io.github.fukusaka.keel.server.http

/** The `bytes` unit token — the only range unit keel parses (RFC 9110 §14.1). */
private const val BYTES_UNIT_PREFIX = "bytes="

/**
 * Upper bound on the number of comma-separated ranges keel will parse and
 * serve. A request listing more ranges has its `Range` header ignored and
 * is served whole.
 *
 * This is the cheap front gate against the 2011 "Apache Killer"
 * (CVE-2011-3192) class of response-amplification attack — a request with
 * a large set of small overlapping ranges. It bounds the CPU spent on
 * parsing and coalescing before any expensive work. RFC 9110 §14.2
 * explicitly permits a server to ignore "a set of many small ranges".
 * Equivalent to nginx `max_ranges` / Apache `MaxRanges`.
 */
internal const val MAX_RANGE_COUNT = 50

/**
 * Outcome of parsing a `Range` request header against an asset of a known
 * size (RFC 9110 §14.2).
 *
 * The serve layer maps each case to a response: [FullResponse] keeps the
 * `200 OK` path, [Single] produces a single-range `206 Partial Content`,
 * [Multiple] produces a `206` with a `multipart/byteranges` body, and
 * [Unsatisfiable] produces a `416 Range Not Satisfiable`.
 */
internal sealed interface RangeResult {

    /**
     * The header is absent, uses a non-`bytes` unit, is syntactically
     * invalid, lists more than [MAX_RANGE_COUNT] ranges, or the resolved
     * satisfiable ranges sum to more bytes than the asset itself. In every
     * case the `Range` header is ignored per RFC 9110 §14.2, so the asset
     * is served whole with `200 OK`.
     */
    data object FullResponse : RangeResult

    /**
     * A single satisfiable range. [start] and [end] are absolute,
     * zero-based, and inclusive; the served length is `end - start + 1`.
     */
    data class Single(val start: Long, val end: Long) : RangeResult

    /**
     * Two or more satisfiable ranges after coalescing — sorted ascending
     * by [ByteRange.start] and pairwise disjoint (RFC 9110 §14.2). The
     * serve layer answers with a `multipart/byteranges` body (§14.6).
     */
    data class Multiple(val ranges: List<ByteRange>) : RangeResult

    /**
     * Every syntactically valid range is unsatisfiable for an asset of the
     * parsed size (each first byte lies at or past the end, or only
     * zero-length suffixes were requested). The serve layer answers `416`
     * with `Content-Range: bytes * /assetSize`.
     */
    data object Unsatisfiable : RangeResult
}

/**
 * A resolved, satisfiable byte range — absolute, zero-based, inclusive
 * bounds. The selected length is `end - start + 1`.
 */
internal data class ByteRange(val start: Long, val end: Long) {

    /** Number of bytes this range selects. */
    val length: Long get() = end - start + 1
}

/**
 * Parses a (possibly multi-range) `Range` request header against
 * [assetSize], per the RFC 9110 §14.2 processing pipeline.
 *
 * Only the `bytes=` unit is handled; any other unit, a syntactically
 * invalid byte-range-set, more than [MAX_RANGE_COUNT] ranges, or a
 * satisfiable-byte sum exceeding [assetSize] all yield
 * [RangeResult.FullResponse] (the `Range` header is ignored). Otherwise
 * the satisfiable ranges are coalesced into an ascending disjoint set:
 * zero left → [RangeResult.Unsatisfiable], one → [RangeResult.Single],
 * two or more → [RangeResult.Multiple].
 *
 * @param headerValue the raw `Range` header field value.
 * @param assetSize the asset size in bytes; ranges resolve against it.
 */
internal fun parseByteRange(headerValue: String, assetSize: Long): RangeResult {
    val trimmed = headerValue.trim()
    if (!trimmed.startsWith(BYTES_UNIT_PREFIX, ignoreCase = true)) return RangeResult.FullResponse
    val spec = trimmed.substring(BYTES_UNIT_PREFIX.length).trim()
    if (spec.isEmpty()) return RangeResult.FullResponse

    // Step 1: split the byte-range-set; any empty element is a syntax error.
    val specs = spec.split(',').map { it.trim() }
    if (specs.any { it.isEmpty() }) return RangeResult.FullResponse

    // Step 2: count cap — a cheap gate bounding parse/coalesce CPU cost.
    if (specs.size > MAX_RANGE_COUNT) return RangeResult.FullResponse

    // Step 3: resolve each spec; a syntax error in any spec ignores the
    // whole header, an unsatisfiable spec is simply dropped.
    val satisfiable = ArrayList<ByteRange>(specs.size)
    for (one in specs) {
        when (val parsed = parseOneSpec(one, assetSize)) {
            is OneSpec.Invalid -> return RangeResult.FullResponse
            OneSpec.Unsatisfiable -> Unit
            is OneSpec.Range -> satisfiable.add(ByteRange(parsed.start, parsed.end))
        }
    }
    if (satisfiable.isEmpty()) return RangeResult.Unsatisfiable

    // Step 4: Σ-bytes — if the resolved satisfiable ranges already select
    // more bytes than the asset, ignore the header (Go http.ServeContent
    // defence; coalescing has not run yet, so this caps amplification).
    var sum = 0L
    for (range in satisfiable) sum += range.length
    if (sum > assetSize) return RangeResult.FullResponse

    // Step 5: coalesce — sort ascending and merge overlapping/adjacent.
    val coalesced = coalesce(satisfiable)

    // Step 6: shape the result.
    return when (coalesced.size) {
        1 -> RangeResult.Single(coalesced[0].start, coalesced[0].end)
        else -> RangeResult.Multiple(coalesced)
    }
}

/** Resolution outcome of a single byte-range-spec within a byte-range-set. */
private sealed interface OneSpec {

    /** A syntactically invalid spec — the whole `Range` header is ignored. */
    data object Invalid : OneSpec

    /** A syntactically valid spec that selects no byte of the asset. */
    data object Unsatisfiable : OneSpec

    /** A satisfiable resolved range with inclusive bounds. */
    data class Range(val start: Long, val end: Long) : OneSpec
}

/** Resolves one byte-range-spec (`START-END`, `START-`, or `-SUFFIXLEN`). */
private fun parseOneSpec(spec: String, assetSize: Long): OneSpec {
    val dash = spec.indexOf('-')
    if (dash < 0) return OneSpec.Invalid
    val startText = spec.substring(0, dash).trim()
    val endText = spec.substring(dash + 1).trim()
    return if (startText.isEmpty()) {
        parseSuffixSpec(endText, assetSize)
    } else {
        parseNormalSpec(startText, endText, assetSize)
    }
}

/** Resolves a `-SUFFIXLEN` suffix spec — the last SUFFIXLEN bytes. */
private fun parseSuffixSpec(suffixText: String, assetSize: Long): OneSpec {
    val suffixLength = suffixText.toLongOrNull() ?: return OneSpec.Invalid
    if (suffixLength < 0) return OneSpec.Invalid
    // A zero-length suffix, or any suffix against an empty asset, selects nothing.
    if (suffixLength == 0L || assetSize == 0L) return OneSpec.Unsatisfiable
    // A suffix at least as long as the asset selects the whole asset.
    val start = if (suffixLength >= assetSize) 0L else assetSize - suffixLength
    return OneSpec.Range(start, assetSize - 1)
}

/** Resolves a `START-END` or `START-` spec; [endText] empty means "to the end". */
private fun parseNormalSpec(startText: String, endText: String, assetSize: Long): OneSpec {
    val start = startText.toLongOrNull() ?: return OneSpec.Invalid
    if (start < 0) return OneSpec.Invalid
    // First byte at or past the end is unsatisfiable.
    if (start >= assetSize) return OneSpec.Unsatisfiable

    val end = if (endText.isEmpty()) {
        assetSize - 1
    } else {
        val parsed = endText.toLongOrNull() ?: return OneSpec.Invalid
        if (parsed < 0) return OneSpec.Invalid
        // A spec whose start lies past its end is a syntax error.
        if (parsed < start) return OneSpec.Invalid
        // Clamp an end past the last byte to the last byte.
        if (parsed >= assetSize) assetSize - 1 else parsed
    }
    return OneSpec.Range(start, end)
}

/**
 * Sorts [ranges] ascending by start and merges overlapping or adjacent
 * ranges, yielding a disjoint ascending set (RFC 9110 §14.2). Two ranges
 * are adjacent when the next one starts at or before one byte past the
 * current end.
 */
private fun coalesce(ranges: List<ByteRange>): List<ByteRange> {
    val sorted = ranges.sortedBy { it.start }
    val merged = ArrayList<ByteRange>(sorted.size)
    var current = sorted[0]
    for (i in 1 until sorted.size) {
        val next = sorted[i]
        if (next.start <= current.end + 1) {
            // Overlapping or adjacent — extend the current range.
            if (next.end > current.end) current = current.copy(end = next.end)
        } else {
            merged.add(current)
            current = next
        }
    }
    merged.add(current)
    return merged
}
