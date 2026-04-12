package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Streaming HTTP body chunk carrying a segment of the message body.
 *
 * Payload is an [IoBuf] owned by this message — the receiver is
 * responsible for calling [IoBuf.release] after consuming the bytes.
 *
 * Open so [HttpBodyEnd] can extend it, allowing downstream handlers
 * to type-dispatch on the common supertype for both mid-body and
 * terminal chunks.
 */
open class HttpBody(
    val content: IoBuf,
) : HttpMessage {

    override fun toString(): String = "HttpBody(${content.readableBytes} bytes)"
}
