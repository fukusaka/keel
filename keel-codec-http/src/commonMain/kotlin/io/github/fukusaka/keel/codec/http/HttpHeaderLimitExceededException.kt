package io.github.fukusaka.keel.codec.http

/**
 * Thrown by [HttpRequestDecoder] when a per-request header limit
 * configured via [HttpHeaderLimitsConfig] is exceeded —
 * `maxHeaderCount`, `maxHeaderBytes`, or `maxLineSize` (the last
 * applied to header / trailer lines; the request-line variant raises
 * the [HttpUriLengthExceededException] subtype so a response mapper
 * can dispatch it to [HttpStatus.URI_TOO_LONG] (414) rather than
 * [HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE] (431)).
 *
 * Subclasses [HttpParseException] so existing pipelines that already
 * catch decoder errors (a malformed request-line, an unsupported HTTP
 * version, etc.) keep working without a new catch arm; an installed
 * `errorHandlers.exceptionMappers` entry can dispatch this specific
 * subtype to a [HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE] response,
 * while the default fallback closes the connection (the same shape
 * the codec already uses for malformed input — and the right
 * disposition for a malicious flood: do not spend write-side resources
 * answering the attacker).
 *
 * **Open** so the URI-specific [HttpUriLengthExceededException]
 * subtype can extend it; a `catch (e: HttpHeaderLimitExceededException)`
 * arm still matches both.
 *
 * @param limitName the configuration field whose cap was exceeded,
 *   e.g. `"maxHeaderCount"`, included verbatim in [message] so log
 *   readers can match it back to the `HttpHeaderLimitsConfig` setter.
 * @param actual the count / size observed when the cap fired.
 * @param limit the cap value from the active [HttpHeaderLimitsConfig].
 */
public open class HttpHeaderLimitExceededException(
    public val limitName: String,
    public val actual: Int,
    public val limit: Int,
) : HttpParseException("HTTP header limit exceeded: $limitName=$actual (cap $limit)")
