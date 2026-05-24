package io.github.fukusaka.keel.codec.http

/**
 * Per-request header limits enforced by [HttpRequestDecoder].
 *
 * Three independent caps form a defence-in-depth around the
 * request-head parse path. Each cap focuses on a different attacker
 * shape:
 *
 * - [maxHeaderCount] caps the **number** of header (or chunked-trailer)
 *   fields. Defends against `Cookie: a=b\r\nCookie: c=d\r\n…` flood
 *   that pads thousands of pairs under any per-line byte cap.
 * - [maxHeaderBytes] caps the **cumulative byte size** of all header
 *   (and trailer) field names + values for one request. Defends
 *   against header-set inflation that stays under the count + per-line
 *   caps but still consumes large recv-buffer and storage budgets.
 * - [maxLineSize] caps the **size of a single line** (request line /
 *   header / trailer / chunk size). Defends against a single
 *   over-sized field (e.g. a megabyte-long URI or a megabyte Cookie
 *   value).
 *
 * Defaults match the ecosystem (Tomcat / Spring Boot / nginx) so a
 * keel-fronted server does not behave unexpectedly relative to its
 * neighbours, and CDN-typical traffic (23 headers, ~1 KiB total
 * field bytes, paths under 1 KiB) stays well below every cap.
 *
 * Build one with the `HttpConnectorBuilder.headerLimits { … }` DSL
 * block; [DEFAULT] is the configuration used when no block is given.
 *
 * @param maxHeaderCount maximum number of header fields a single
 *   request head (or chunked-trailer block) may carry before parsing
 *   is aborted with [HttpHeaderLimitExceededException].
 * @param maxHeaderBytes maximum cumulative bytes (sum of every field's
 *   name length + value length, headers + trailers) one request may
 *   carry before parsing is aborted with
 *   [HttpHeaderLimitExceededException]. Independent of
 *   [maxHeaderCount] — a few very long headers and many tiny ones are
 *   both bounded.
 * @param maxLineSize maximum bytes of a single parsed line (request
 *   line / one header / one trailer / one chunk size). Replaces the
 *   historical hardcoded 8 KiB cap. Over-cap on the **request line**
 *   raises the URI-specific [HttpUriLengthExceededException] subtype
 *   so a response mapper can dispatch it to
 *   [HttpStatus.URI_TOO_LONG] (414); over-cap on a header / trailer
 *   line raises [HttpHeaderLimitExceededException] (→ 431). Must be a
 *   power of two in 1 KiB..1 MiB.
 */
public class HttpHeaderLimitsConfig public constructor(
    public val maxHeaderCount: Int,
    public val maxHeaderBytes: Int = DEFAULT_MAX_HEADER_BYTES,
    public val maxLineSize: Int = DEFAULT_MAX_LINE_SIZE,
) {

    init {
        require(maxHeaderCount > 0) { "maxHeaderCount ($maxHeaderCount) must be positive" }
        require(maxHeaderBytes > 0) { "maxHeaderBytes ($maxHeaderBytes) must be positive" }
        requireValidMaxLineSize(maxLineSize)
    }

    public companion object {

        /**
         * Default header count cap — `100` fields.
         *
         * Matches Tomcat's `maxHeaderCount` default and Spring Boot's
         * `server.tomcat.max-header-count` default. Comfortably above
         * the CDN-typical 23-header request the keel codec is
         * regression-benched against, while still capping a flood of
         * tiny headers from a malicious client.
         */
        public const val DEFAULT_MAX_HEADER_COUNT: Int = 100

        /**
         * Default cumulative header-bytes cap — `16384` bytes (16 KiB).
         *
         * Sits between Tomcat's `maxHttpHeaderSize` default (8 KiB,
         * configurable up to 32 KiB) and nginx's `large_client_header_buffers`
         * (`4 × 8 KiB = 32 KiB` total). 16 KiB admits CDN-typical
         * requests (~1 KiB header bytes, generous reverse-proxy
         * additions) while bounding cumulative buffer / storage
         * consumption.
         */
        public const val DEFAULT_MAX_HEADER_BYTES: Int = 16384

        /**
         * Default per-line size cap — `8192` bytes (8 KiB).
         *
         * Matches Netty / Tomcat / Jetty / nginx defaults and the
         * historical hardcoded `MAX_LINE_SIZE` in [HttpRequestDecoder].
         * Byte-identical to the pre-config behaviour so existing
         * deployments are unaffected by the new knob.
         */
        public const val DEFAULT_MAX_LINE_SIZE: Int = 8192

        private const val MIN_VALID_MAX_LINE_SIZE: Int = 1024
        private const val MAX_VALID_MAX_LINE_SIZE: Int = 1024 * 1024

        /**
         * Throws [IllegalArgumentException] when [value] is not a
         * power of two in `1 KiB..1 MiB`. The codec's accumulator
         * doubles its capacity on demand and the doubling math is
         * simplest when the cap is itself a power of two; the range
         * matches the engine's recv-buffer validator
         * `IoEngineConfig.requireValidReadBufferSize`.
         */
        internal fun requireValidMaxLineSize(value: Int) {
            require(value in MIN_VALID_MAX_LINE_SIZE..MAX_VALID_MAX_LINE_SIZE) {
                "maxLineSize ($value) must be in $MIN_VALID_MAX_LINE_SIZE..$MAX_VALID_MAX_LINE_SIZE bytes"
            }
            require(value and (value - 1) == 0) {
                "maxLineSize ($value) must be a power of two"
            }
        }

        /**
         * The default configuration — [DEFAULT_MAX_HEADER_COUNT]
         * headers, [DEFAULT_MAX_HEADER_BYTES] total bytes,
         * [DEFAULT_MAX_LINE_SIZE] per-line bytes.
         */
        public val DEFAULT: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig(
            maxHeaderCount = DEFAULT_MAX_HEADER_COUNT,
            maxHeaderBytes = DEFAULT_MAX_HEADER_BYTES,
            maxLineSize = DEFAULT_MAX_LINE_SIZE,
        )
    }
}
