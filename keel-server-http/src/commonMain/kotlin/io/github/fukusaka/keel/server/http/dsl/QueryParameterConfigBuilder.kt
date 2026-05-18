package io.github.fukusaka.keel.server.http.dsl

import io.github.fukusaka.keel.server.dsl.KeelServerDsl
import io.github.fukusaka.keel.server.http.QueryParameterConfig

/**
 * Builder for [QueryParameterConfig], used by the
 * [HttpConnectorBuilder.queryParameters] DSL block.
 *
 * Each property defaults to the matching [QueryParameterConfig.DEFAULT]
 * value, so a block need set only the knobs it wants to change.
 */
@KeelServerDsl
public class QueryParameterConfigBuilder internal constructor() {

    /**
     * Maximum number of `name=value` pairs (counting duplicates) a query
     * string may carry. Exceeding it answers the request `400`. Defaults
     * to [QueryParameterConfig.DEFAULT]'s value.
     */
    public var maxParameterCount: Int = QueryParameterConfig.DEFAULT.maxParameterCount

    /**
     * When `true`, a decoded name or value containing a C0 control byte
     * or `DEL` answers the request `400`. Defaults to `false`.
     */
    public var rejectControlCharacters: Boolean = QueryParameterConfig.DEFAULT.rejectControlCharacters

    /**
     * When `true`, a malformed `%` escape or invalid UTF-8 in the
     * decoded bytes answers the request `400`. Defaults to `false`.
     */
    public var rejectMalformedEncoding: Boolean = QueryParameterConfig.DEFAULT.rejectMalformedEncoding

    internal fun build(): QueryParameterConfig = QueryParameterConfig(
        maxParameterCount = maxParameterCount,
        rejectControlCharacters = rejectControlCharacters,
        rejectMalformedEncoding = rejectMalformedEncoding,
    )
}
