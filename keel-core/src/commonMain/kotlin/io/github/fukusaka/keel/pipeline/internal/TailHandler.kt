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
        // its drain could not finish -- a buffer that would not release --
        // as suppressed causes, and they are named here because they arrive
        // attached to this one instance. (A wind-down step that threw does
        // not ride: it happens after the instance was published, so its
        // record is the transport's own warn.) A handler that takes the
        // error and does not pass it on takes those with it, which is the
        // same trade the pipeline has always made for anything it absorbs.
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

    /**
     * The peer's end of file that the chain turned down.
     *
     * A handler that takes this event and does not pass it on claims the
     * connection: it may answer the peer that half-closed, and it closes
     * when it is done. One that passes it on has said the connection is not
     * its to end, and when every handler has said so the connection has
     * nobody left to answer for it — so the tail closes, which releases the
     * descriptor that would otherwise sit in CLOSE-WAIT and delivers the
     * ending the chain still needs.
     *
     * Not for an event the chain has not finished being offered: a context
     * that had yet to activate is offered it when it does, and the tail is
     * asked again after that. Not for an empty chain either — nobody has
     * turned it down, and the report waits for the first handler to arrive —
     * in the journal while one is still filling, and in the record of an
     * offer nobody has been made once it has drained.
     *
     * Not for an end of file a handler raised, either. That one says one
     * handler's own output is over, which leaves the descriptor open in both
     * directions and the handlers above it reading, so the CLOSE-WAIT this
     * close exists to release is not what a raise leaves behind. The answer
     * goes back to the handler that raised it instead.
     *
     * **And a handler that took either kind has taken the connection, not
     * the event.** So once one has, this closes for nothing afterwards —
     * including the transport's own report, which such a handler is not even
     * told about, since it has heard the read side end once already and
     * hears it once. What looks from here like a report nobody answered is a
     * report whose answer was given earlier, by a handler that owed the close —
     * one that may since have left the chain, which does not give the claim
     * back. Where a read-idle timeout is configured it reclaims a connection
     * whose claimant never closes; it is off by default, and there the
     * connection is held until its owner closes it.
     */
    override fun onReadClosed(ctx: PipelineHandlerContext) {
        if (!pipeline.readClosed.refusedByAll) return
        // Asked for, not performed here: the event is still travelling, and
        // a handler joining behind it is owed the offer before the
        // connection goes. The frame's epilogue performs it.
        pipeline.closeOwedByTail = true
    }

    override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
        logger.warn { "Unhandled user event reached TAIL: ${event::class.simpleName}" }
    }
}
