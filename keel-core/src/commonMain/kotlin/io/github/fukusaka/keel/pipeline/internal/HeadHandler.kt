package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.logging.debug
import io.github.fukusaka.keel.logging.warn
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.OutboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext

/**
 * The head of the pipeline — connects inbound/outbound to the [IoTransport].
 *
 * **Inbound**: propagates events to the next handler (acts as the entry point).
 * **Outbound**: terminates the chain by delegating to the transport — and,
 * for a refused send, is where that failure stops travelling, so it is also
 * where one that reached no handler is recorded.
 *
 * HeadHandler implements both [InboundHandler] and [OutboundHandler]
 * so it participates in both directions of the pipeline.
 */
internal class HeadHandler(
    private val transport: IoTransport,
    private val pipeline: DefaultPipeline,
) : DuplexHandler {

    // --- Inbound: pass through to next handler ---

    // Default implementations from InboundHandler propagate automatically.
    // HeadHandler does not transform inbound messages.

    // --- Outbound: terminate at transport ---

    override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is IoBuf) {
            transport.write(msg)
        } else {
            // Non-IoBuf messages cannot be written to the transport.
            // Release if possible and report error.
            ReferenceCountUtil.safeRelease(msg)
            ctx.propagateError(
                UnsupportedOperationException(
                    "Cannot write ${msg::class.simpleName} to transport; expected IoBuf",
                ),
            )
        }
    }

    override fun onFlush(ctx: PipelineHandlerContext) {
        try {
            transport.flush()
        } catch (refused: RefusedWriteException) {
            // The one frame that silences a refusal, so it records what these
            // handlers are not getting. Never converted into an error for
            // them: the transport delivers the one it reports, riders
            // attached as suppressed causes, before the connection ends --
            // converting the rethrow would tell them the same instance twice,
            // and re-entering them from here is unbounded recursion, since a
            // handler that answers every error with another doomed write
            // mints a fresh refusal each time.
            //
            // Reaching here at all means the drain ran inside this call, so
            // no loop containment saw it and no other frame will name it. The
            // refusal these handlers are getting is the one exception: they
            // have it, or a scheduled replay is bringing it, and naming it
            // here would report the same thing twice. A journal with no
            // replay scheduled is not that, and neither is a refusal raised
            // by anything but the transport, nor a channel with nothing
            // installed -- there no frame below keeps a record, so this one
            // does, where a channel with handlers has the end of the
            // pipeline for that.
            //
            // At the level the end of the pipeline uses, and for the same
            // reason: a connection the transport gave up on is an outcome,
            // not a fault to look into -- the send that ended it is named in
            // the record's cause, errno and all, for a reader who goes
            // looking, and a caller that waits on the flush is answered with
            // the same instance. What rode along on it is not an outcome --
            // a buffer the drain could not release -- and nothing else will
            // name those, so they make the record loud and are carried by
            // it. (A wind-down step that threw does not ride: it happens
            // after the instance was published, so its record is the
            // transport's own warn.) Without this the same connection read
            // as routine with a bridge installed and as a problem without
            // one; the engine's own containment still warns for the deferred
            // drain on the shipping default, which is where a reader who
            // watches warnings sees it. Under the coalescing opt-out the
            // drain runs here instead, so this record is the only one, and a
            // reader who wants those goes looking for them -- which is the
            // trade for not reporting an ordinary end as a fault.
            //
            // Only the refusal, not its sealed supertype: the siblings do
            // not take this route at all. One is the loop itself being gone,
            // which no handler on it can act on. The other ends the
            // connection, and the inactive report is how a handler hears
            // that -- it arrives either way, and where the failure came from
            // this chain, handing a handler its own throw would invite an
            // answer that throws again.
            // "Ended at the head", not "was contained": this frame cannot
            // see whether a settlement ran. A transport-minted refusal
            // arrives settled; one minted by application code inside the
            // flush (a completion callback throwing the public type) arrives
            // unsettled and unrecorded, and claiming containment for it
            // would be the record lying about the one case it exists for.
            if (!pipeline.handlersAreGettingTransportFailure(refused)) {
                if (refused.suppressedExceptions.isEmpty()) {
                    pipeline.logger.debug(refused) {
                        "a refusal ended at the head before any handler had it"
                    }
                } else {
                    pipeline.logger.warn(refused) {
                        "a refusal ended at the head before any handler had it, and something failed with it"
                    }
                }
            }
        }
    }

    override fun onClose(ctx: PipelineHandlerContext) {
        try {
            transport.close()
        } catch (refused: Throwable) {
            // Wrapped so it can be told from a handler's own failure. A
            // handler's `onClose` calls `propagateClose()` from inside its own
            // body, so the invoker's catch is around both — there is no frame
            // that sees only one of the two, and position cannot separate
            // them. This one belongs to whoever asked to close: a release that
            // refused is the answer to their question, not an event for the
            // chain to be told about.
            throw TerminusCloseFailure(refused)
        }
    }
}

/**
 * A refusal from the end of the close walk, on its way back to the caller.
 *
 * Carried rather than reported, and unwrapped by [DefaultPipeline] before it
 * leaves: what the caller sees is the transport's own exception, the same one
 * they saw when they closed the transport themselves. Nothing catches this
 * type on the way out — that is its whole purpose.
 */
internal class TerminusCloseFailure(override val cause: Throwable) : Throwable(cause)
