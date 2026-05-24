package io.github.fukusaka.keel.server.http.dsl

import io.github.fukusaka.keel.codec.http.HttpHeaderLimitExceededException
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.HttpUriLengthExceededException

/**
 * Registers the canonical RFC-aligned response mappers for the codec's
 * DoS-limit exceptions on this builder:
 *
 * - [HttpUriLengthExceededException] → [HttpStatus.URI_TOO_LONG] (414,
 *   RFC 7231 §6.5.12)
 * - [HttpHeaderLimitExceededException] → [HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE]
 *   (431, RFC 6585 §5)
 *
 * Mapper order matters: [HttpUriLengthExceededException] is a subtype
 * of [HttpHeaderLimitExceededException] (a 414-eligible request-line
 * over-cap would otherwise match the generic 431 mapper first), so the
 * URI subtype is registered ahead of the parent.
 *
 * Usage:
 *
 * ```kotlin
 * keelHttpServer(engine) {
 *     connector { headerLimits { maxHeaderCount = 100; maxLineSize = 8192 } }
 *     dosLimitResponses()
 * }
 * ```
 *
 * Without this call, the codec falls back to the existing
 * connection-close disposition on any [HttpHeaderLimitExceededException]
 * (which is also a reasonable choice for a malicious peer — see
 * [HttpHeaderLimitExceededException] KDoc). Install this when the
 * application would rather answer the offending request with a status
 * code and continue serving other connections.
 */
public fun KeelHttpServerBuilder.dosLimitResponses() {
    // More specific subtype first — the generic exception<T> dispatch is
    // first-match-by-type-instance, so HttpUriLengthExceededException
    // must be registered ahead of its parent to reach the 414 mapper.
    exception<HttpUriLengthExceededException> { call, _ ->
        call.respondText("Request-URI too long", HttpStatus.URI_TOO_LONG)
    }
    exception<HttpHeaderLimitExceededException> { call, _ ->
        call.respondText("Request header fields too large", HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE)
    }
}
