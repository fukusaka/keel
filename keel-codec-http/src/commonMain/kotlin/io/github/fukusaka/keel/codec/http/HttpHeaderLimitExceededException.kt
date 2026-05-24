package io.github.fukusaka.keel.codec.http

/**
 * Thrown by [HttpRequestDecoder] when a per-request header limit
 * configured via [HttpHeaderLimitsConfig] is exceeded — currently the
 * `maxHeaderCount` count cap; total-bytes and per-line caps will
 * extend this in a follow-up.
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
 * @param limitName the configuration field whose cap was exceeded,
 *   e.g. `"maxHeaderCount"`, included verbatim in [message] so log
 *   readers can match it back to the `HttpHeaderLimitsConfig` setter.
 * @param actual the count / size observed when the cap fired.
 * @param limit the cap value from the active [HttpHeaderLimitsConfig].
 */
public class HttpHeaderLimitExceededException(
    public val limitName: String,
    public val actual: Int,
    public val limit: Int,
) : HttpParseException("HTTP header limit exceeded: $limitName=$actual (cap $limit)")
