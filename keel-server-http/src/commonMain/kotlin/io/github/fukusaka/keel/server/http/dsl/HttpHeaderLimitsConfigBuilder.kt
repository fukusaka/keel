package io.github.fukusaka.keel.server.http.dsl

import io.github.fukusaka.keel.codec.http.HttpHeaderLimitsConfig
import io.github.fukusaka.keel.server.dsl.KeelServerDsl

/**
 * Builder for [HttpHeaderLimitsConfig], used by the
 * [HttpConnectorBuilder.headerLimits] DSL block.
 *
 * Each property defaults to the matching
 * [HttpHeaderLimitsConfig.DEFAULT] value, so a block need set only the
 * knobs it wants to change. Shaped after [QueryParameterConfigBuilder]
 * so the two DoS-guard DSL blocks present a uniform surface.
 */
@KeelServerDsl
public class HttpHeaderLimitsConfigBuilder internal constructor() {

    /**
     * Maximum number of header fields a single request head (or
     * chunked-trailer block) may carry. Exceeding it aborts parsing
     * with `HttpHeaderLimitExceededException`. Defaults to
     * [HttpHeaderLimitsConfig.DEFAULT_MAX_HEADER_COUNT] (`100`,
     * matching Tomcat / Spring Boot conventions).
     */
    public var maxHeaderCount: Int = HttpHeaderLimitsConfig.DEFAULT.maxHeaderCount

    /**
     * Maximum cumulative bytes (sum of every field's name length +
     * value length, headers + trailers) one request may carry.
     * Exceeding it aborts parsing with
     * `HttpHeaderLimitExceededException`. Defaults to
     * [HttpHeaderLimitsConfig.DEFAULT_MAX_HEADER_BYTES] (`16384`).
     */
    public var maxHeaderBytes: Int = HttpHeaderLimitsConfig.DEFAULT.maxHeaderBytes

    /**
     * Maximum bytes of a single parsed line (request line / one
     * header / one trailer / one chunk size). Over-cap on the
     * **request line** aborts with `HttpUriLengthExceededException`
     * (the URI-specific subtype, → `HttpStatus.URI_TOO_LONG`);
     * over-cap on header / trailer aborts with the generic
     * `HttpHeaderLimitExceededException` (→
     * `HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE`). Must be a power
     * of two in `1 KiB..1 MiB`. Defaults to
     * [HttpHeaderLimitsConfig.DEFAULT_MAX_LINE_SIZE] (`8192`,
     * byte-identical to the pre-config historical cap).
     */
    public var maxLineSize: Int = HttpHeaderLimitsConfig.DEFAULT.maxLineSize

    internal fun build(): HttpHeaderLimitsConfig = HttpHeaderLimitsConfig(
        maxHeaderCount = maxHeaderCount,
        maxHeaderBytes = maxHeaderBytes,
        maxLineSize = maxLineSize,
    )
}
