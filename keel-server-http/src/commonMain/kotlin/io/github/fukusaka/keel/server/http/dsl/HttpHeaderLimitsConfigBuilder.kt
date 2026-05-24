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

    internal fun build(): HttpHeaderLimitsConfig = HttpHeaderLimitsConfig(
        maxHeaderCount = maxHeaderCount,
    )
}
