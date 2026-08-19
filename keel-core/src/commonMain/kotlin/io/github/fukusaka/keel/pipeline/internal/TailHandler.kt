package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.core.TransportFailureException
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.OutboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext

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
        // A connection the transport gave up on is not an application bug,
        // and reaching here is not evidence of one: the reason is delivered
        // to the handlers ahead of the end they clean up on, and acting on
        // it is optional -- most handlers have nothing to do with the reason
        // that the end does not already tell them. Recording it at the level
        // that matches how ordinary it is keeps a peer disappearing
        // mid-write off the list of things a reader is asked to investigate.
        //
        // Unless something failed alongside it. A refusal carries what could
        // not be finished while it was being contained -- a buffer that
        // would not release, a wind-down step that threw -- as suppressed
        // causes, and those are not ordinary. They arrive attached to this
        // one instance, so this is where they are named.
        if (cause is TransportFailureException && cause.suppressedExceptions.isEmpty()) {
            logger.debug(cause) { "the connection failed and no handler acted on the reason" }
            return
        }
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
