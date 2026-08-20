package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.RefusedWriteException
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
            // by anything but the transport. Nor is a channel with nothing
            // installed: its caller is answered by the wait, but no frame
            // below keeps a record, so this one does -- where a channel with
            // handlers has the end of the pipeline for that. Everything else is
            // recorded -- one line, carrying the refusal, so whatever rode
            // along on it is named with it. Only the refusal, not its sealed
            // supertype: the sibling failure is not raised anywhere yet, and
            // how it should reach handlers is settled with the work that
            // starts raising it.
            if (!pipeline.handlersAreGettingTransportFailure(refused)) {
                pipeline.logger.warn(refused) {
                    "a refused send was contained without reaching these handlers"
                }
            }
        }
    }

    override fun onClose(ctx: PipelineHandlerContext) {
        transport.close()
    }
}
