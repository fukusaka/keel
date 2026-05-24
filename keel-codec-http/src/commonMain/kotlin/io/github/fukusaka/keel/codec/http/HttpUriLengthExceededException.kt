package io.github.fukusaka.keel.codec.http

/**
 * Thrown by [HttpRequestDecoder] when the **request-line** (which in
 * practice is dominated by the URI) exceeds the configured
 * [HttpHeaderLimitsConfig.maxLineSize].
 *
 * Subclasses [HttpHeaderLimitExceededException] so a handler that
 * catches the generic exception keeps working, while an installed
 * `errorHandlers.exceptionMappers` entry can dispatch this specific
 * subtype to a [HttpStatus.URI_TOO_LONG] (414) response — separate
 * from the [HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE] (431) response
 * that the bare [HttpHeaderLimitExceededException] maps to. RFC 7231
 * §6.5.12 specifies 414 for the URI-too-long case; RFC 6585 §5
 * specifies 431 for header-set-too-large; keel's exception hierarchy
 * mirrors the RFC distinction so a single mapper can route both
 * correctly.
 *
 * The [limitName] for instances of this subtype is always
 * `"maxLineSize (request line)"` so log readers can tell at a glance
 * which line the over-cap fired on.
 *
 * @param actual the request-line byte length observed when the cap
 *   fired (one past the cap, since the check runs after the line
 *   boundary has been determined).
 * @param limit the [HttpHeaderLimitsConfig.maxLineSize] value the
 *   request line was measured against.
 */
public class HttpUriLengthExceededException(
    actual: Int,
    limit: Int,
) : HttpHeaderLimitExceededException(
    limitName = "maxLineSize (request line)",
    actual = actual,
    limit = limit,
)
