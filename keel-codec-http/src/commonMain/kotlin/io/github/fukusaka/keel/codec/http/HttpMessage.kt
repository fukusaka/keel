package io.github.fukusaka.keel.codec.http

/**
 * Marker interface for pipeline HTTP messages emitted by [HttpRequestDecoder]
 * and accepted by [HttpResponseEncoder].
 *
 * The streaming wire protocol is a sequence of one message head
 * ([HttpRequestHead] or [HttpResponseHead]) followed by zero or more
 * [HttpBody] chunks terminated by exactly one [HttpBodyEnd]:
 *
 * ```
 * HttpRequestHead → HttpBody × N → HttpBodyEnd
 * ```
 *
 * Handlers that accept streaming HTTP messages should declare
 * `acceptedType = HttpMessage::class` to receive all subtypes.
 */
sealed interface HttpMessage
