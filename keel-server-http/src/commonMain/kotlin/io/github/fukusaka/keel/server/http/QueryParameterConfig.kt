package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.server.http.dsl.HttpConnectorBuilder

/**
 * Security configuration for query-string parsing (see
 * [parseQueryParameters]).
 *
 * Two layers bound a query string. The HTTP request line is already
 * capped at 8192 bytes by `HttpRequestDecoder` in `keel-codec-http`, so
 * the total query-string length cannot be unbounded. [maxParameterCount]
 * adds the explicit DoS guard the byte cap does not give on its own: a
 * short query string can still pack thousands of `&`-separated pairs and
 * blow up the parsed map and the per-parameter work done downstream.
 *
 * The two `reject*` flags are opt-in strictness. They are off by default
 * so the parser stays lenient (a malformed `%` is kept literal, invalid
 * UTF-8 becomes `U+FFFD`); turn them on to answer a malformed query
 * `400 Bad Request` at the server's edge instead.
 *
 * Build one with the [HttpConnectorBuilder.queryParameters] DSL block;
 * [DEFAULT] is the configuration used when no block is given.
 *
 * @param maxParameterCount maximum number of `name=value` pairs (counting
 *   duplicates) a query string may carry before it is rejected.
 * @param rejectControlCharacters when `true`, a decoded name or value
 *   containing a C0 control byte (`0x00`..`0x1F`) or `DEL` (`0x7F`) is
 *   rejected.
 * @param rejectMalformedEncoding when `true`, a malformed `%` escape or
 *   invalid UTF-8 in the percent-decoded bytes is rejected.
 */
public class QueryParameterConfig internal constructor(
    public val maxParameterCount: Int,
    public val rejectControlCharacters: Boolean,
    public val rejectMalformedEncoding: Boolean,
) {

    public companion object {

        /**
         * Default query-parameter limit — `1000` pairs.
         *
         * Matches the de-facto convention shared by Tomcat, Jetty,
         * Undertow, PHP, and the `qs` library: large enough for any
         * legitimate form post, small enough to cap a flood of pairs.
         */
        private const val DEFAULT_MAX_PARAMETER_COUNT: Int = 1000

        /**
         * The default configuration — [DEFAULT_MAX_PARAMETER_COUNT]
         * parameters, lenient decoding (both `reject*` flags off).
         */
        public val DEFAULT: QueryParameterConfig = QueryParameterConfig(
            maxParameterCount = DEFAULT_MAX_PARAMETER_COUNT,
            rejectControlCharacters = false,
            rejectMalformedEncoding = false,
        )
    }
}
