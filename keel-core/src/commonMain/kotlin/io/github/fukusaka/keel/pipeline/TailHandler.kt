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
 * **Outbound**: TailHandler does not implement [OutboundHandler].
 * Outbound operations start from the tail context and flow toward HEAD.
 */
internal class TailHandler(
    private val logger: Logger,
) : InboundHandler {

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        logger.warn { "Unhandled inbound message reached TAIL: ${msg::class.simpleName}. Releasing." }
        ReferenceCountUtil.safeRelease(msg)
    }

    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
        logger.warn(cause) { "Unhandled exception reached TAIL" }
    }

    override fun onActive(ctx: PipelineHandlerContext) {
        // Terminal — do not propagate.
    }

    override fun onReadComplete(ctx: PipelineHandlerContext) {
        // Terminal — do not propagate.
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        // Terminal — do not propagate.
    }

    override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
        logger.warn { "Unhandled user event reached TAIL: ${event::class.simpleName}" }
    }
}
