package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Streaming HTTP body chunk carrying a segment of the message body.
 *
 * Payload is an [IoBuf] owned by this message — the receiver is
 * responsible for calling [release] (or [close]) after consuming
 * the bytes to avoid a resource leak.
 *
 * Implements [AutoCloseable] so that pipeline infrastructure can
 * reclaim the owned [IoBuf] without a direct dependency on
 * [HttpBody] — the pipeline calls [AutoCloseable.close] on
 * undeliverable messages the same way it calls [IoBuf.release] on
 * raw buffers.
 *
 * Open so [HttpBodyEnd] can extend it, allowing downstream handlers
 * to type-dispatch on the common supertype for both mid-body and
 * terminal chunks.
 */
open class HttpBody(
    val content: IoBuf,
) : HttpMessage, AutoCloseable {

    /** Releases the owned [content] buffer. Idempotent via [IoBuf.release]. */
    fun release(): Boolean = content.release()

    /** Delegates to [release]; satisfies [AutoCloseable] for pipeline cleanup. */
    override fun close() { content.release() }

    override fun toString(): String = "HttpBody(${content.readableBytes} bytes)"
}
