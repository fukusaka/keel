package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.core.RefusedWriteException
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
        // A refused send is not an application bug, and reaching here is not
        // evidence of one: the reason is delivered to the handlers ahead of
        // the end they clean up on, and most have nothing to do with it that
        // the end does not already tell them. What reaching here means is
        // that nothing stopped it on the way, which is ordinary -- so it is
        // recorded at the level that says so rather than reported as an
        // exception nobody handled. On a coalescing default the engine's own
        // containment writes its warning for the same send, so what this
        // spares a reader is the second, misleading line.
        //
        // The refusal only, not its sealed supertype: a loop that ended
        // without being asked to takes every connection it served with it,
        // which is not ordinary at all, and how that failure should reach
        // handlers is settled with the work that starts raising it.
        //
        // And not when something failed alongside it. A refusal carries what
        // could not be finished while it was being contained -- a buffer
        // that would not release, a wind-down step that threw -- as
        // suppressed causes. Those arrive attached to this one instance, so
        // this is where they are named.
        if (cause is RefusedWriteException && cause.suppressedExceptions.isEmpty()) {
            logger.debug(cause) { "a refused send reached the end of the pipeline" }
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
