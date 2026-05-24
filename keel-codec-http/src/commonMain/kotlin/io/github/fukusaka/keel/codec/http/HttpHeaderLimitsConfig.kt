package io.github.fukusaka.keel.codec.http

/**
 * Per-request header limits enforced by [HttpRequestDecoder].
 *
 * Decouples the DoS guard from the line-size cap that
 * `HttpRequestDecoder` already imposes (8 KiB per line, per RFC 7230
 * §3.2.5 conventional implementation limits). The per-line cap on its
 * own does not block a malicious client from sending an unbounded
 * number of small headers — `Cookie: a=b\r\nCookie: c=d\r\n...` can
 * fit thousands of pairs under the line cap, blowing up the parsed
 * [HttpHeaders] storage and the per-header work the codec layer + the
 * handler layer do on top of it.
 *
 * [maxHeaderCount] is the count-based cap that closes that gap. The
 * default ([DEFAULT_MAX_HEADER_COUNT] = `100`) matches Tomcat's
 * `maxHeaderCount` and Spring Boot's `server.tomcat.max-header-count`,
 * is comfortably larger than a CDN-typical request set (~23 headers in
 * the HTTP Archive sample the keel codec is benched against), and is
 * the de-facto convention in the ecosystem so a keel-fronted server
 * does not behave unexpectedly relative to other servers' defaults.
 *
 * Total-bytes-of-headers and per-line-size caps belong on the same
 * config but are not part of this struct yet — they land in a
 * follow-up alongside the [HttpHeaderLimitExceededException] →
 * [HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE] response wiring.
 *
 * Build one with the `HttpConnectorBuilder.headerLimits { … }` DSL
 * block; [DEFAULT] is the configuration used when no block is given.
 *
 * @param maxHeaderCount maximum number of header fields a single
 *   request head (or chunked-trailer block) may carry before parsing is
 *   aborted with [HttpHeaderLimitExceededException].
 */
public class HttpHeaderLimitsConfig public constructor(
    public val maxHeaderCount: Int,
) {

    init {
        require(maxHeaderCount > 0) { "maxHeaderCount ($maxHeaderCount) must be positive" }
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

        /** The default configuration — [DEFAULT_MAX_HEADER_COUNT] headers. */
        public val DEFAULT: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig(
            maxHeaderCount = DEFAULT_MAX_HEADER_COUNT,
        )
    }
}
