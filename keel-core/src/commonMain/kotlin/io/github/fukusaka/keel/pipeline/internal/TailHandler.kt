package io.github.fukusaka.keel.pipeline.internal

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
    private val pipeline: DefaultPipeline,
) : InboundHandler {

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        logger.warn { "Unhandled inbound message reached TAIL: ${msg::class.simpleName}. Releasing." }
        ReferenceCountUtil.safeRelease(msg)
    }

    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
        // The refused send this pipeline was told about is not an
        // application bug, and reaching here is not evidence of one: the
        // reason is delivered ahead of the end the handlers clean up on, and
        // most have nothing to do with it that the end does not already tell
        // them. What reaching here means is that nothing stopped it on the
        // way, which is ordinary -- so it is recorded at the level that says
        // so. On a coalescing default the engine's own containment writes a
        // warning for the same send, so what this spares a reader is the
        // second, misleading line.
        //
        // By identity, not by type: the transport reports one instance and
        // this is it, whether it arrived now or by a replay later. A refusal
        // a handler threw, or one an application injected through the public
        // error entrance, is not that -- and a handler throwing anything is
        // the case this frame exists to report.
        //
        // The head asks a different question of the same mark -- whether the
        // handlers are getting it -- because it decides before they do.
        // Standing here, that has already happened, so the two questions
        // cannot be told apart from this frame; the one asked is the one
        // this frame means.
        //
        // And not when something failed alongside it. A refusal carries what
        // could not be finished while it was being contained -- a buffer
        // that would not release, a wind-down step that threw -- as
        // suppressed causes, which are named here because they arrive
        // attached to this one instance and nothing else will name them.
        if (cause === pipeline.reportedTransportFailure) {
            if (cause.suppressedExceptions.isEmpty()) {
                logger.debug(cause) { "a refused send reached the end of the pipeline" }
            } else {
                logger.warn(cause) { "a refused send reached the end of the pipeline, and something failed with it" }
            }
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
