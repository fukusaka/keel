package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.warn

/**
 * The tail of the pipeline — safety net for unhandled messages and events.
 *
 * **Inbound**: releases any [io.github.fukusaka.keel.buf.IoBuf] messages that
 * reached the tail without being consumed, and logs a warning. This prevents
 * buffer leaks when a handler forgets to consume or propagate a message.
 *
 * **Outbound**: TailHandler does not implement [ChannelOutboundHandler].
 * Outbound operations start from the tail context and flow toward HEAD.
 */
internal class TailHandler(
    private val logger: Logger,
) : ChannelInboundHandler {

    override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        logger.warn { "Unhandled inbound message reached TAIL: ${msg::class.simpleName}. Releasing." }
        ReferenceCountUtil.safeRelease(msg)
    }

    override fun onError(ctx: ChannelHandlerContext, cause: Throwable) {
        logger.warn(cause) { "Unhandled exception reached TAIL" }
    }

    override fun onActive(ctx: ChannelHandlerContext) {
        // Terminal — do not propagate.
    }

    override fun onReadComplete(ctx: ChannelHandlerContext) {
        // Terminal — do not propagate.
    }

    override fun onInactive(ctx: ChannelHandlerContext) {
        // Terminal — do not propagate.
    }

    override fun onUserEvent(ctx: ChannelHandlerContext, event: Any) {
        logger.warn { "Unhandled user event reached TAIL: ${event::class.simpleName}" }
    }
}
