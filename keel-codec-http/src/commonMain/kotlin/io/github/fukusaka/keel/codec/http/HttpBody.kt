package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.Releasable

/**
 * Streaming HTTP body chunk carrying a segment of the message body.
 *
 * Payload is an [IoBuf] owned by this message — the receiver is
 * responsible for calling [release] after consuming the bytes to
 * avoid a resource leak.
 *
 * Implements [Releasable] so that pipeline infrastructure can reclaim
 * the owned [IoBuf] without a direct dependency on [HttpBody] — the
 * pipeline's error-cleanup path calls [Releasable.release] on
 * undeliverable messages the same way it does on raw [IoBuf] instances.
 *
 * Open so [HttpBodyEnd] can extend it, allowing downstream handlers
 * to type-dispatch on the common supertype for both mid-body and
 * terminal chunks.
 */
open class HttpBody(
    val content: IoBuf,
) : HttpMessage, Releasable {

    /** Releases the owned [content] buffer. */
    override fun release(): Boolean = content.release()

    override fun toString(): String = "HttpBody(${content.readableBytes} bytes)"
}
