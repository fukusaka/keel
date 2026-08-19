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
 * **Outbound**: terminates the chain by delegating to the transport.
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
            // Never converted. A refusal has one construction site and every
            // raise passes through the transport's flush funnel: the first
            // on a live connection is delivered to this pipeline -- riders
            // included, as suppressed causes -- before the connection ends,
            // so converting the rethrow would tell the same handlers the
            // same instance twice, and one the funnel stays quiet about
            // (the caller was closing, or the reason is an earlier refusal)
            // is quiet by design.
            //
            // But this is the one frame that *silences*, so it checks what
            // it silences: a failed release riding on a refusal the funnel
            // stayed quiet about has no other reporter, and a leak is never
            // silent. Named in the log, deliberately not handed back to the
            // handlers: re-entering them from here opened an unbounded
            // recursion -- a handler that answers every error with another
            // doomed write mints a fresh rider each time -- and the reported
            // refusal's riders were already delivered attached, where a
            // second delivery would land after the inactive they precede.
            if (refused !== pipeline.lastNotifiedError && refused.suppressedExceptions.isNotEmpty()) {
                pipeline.logger.warn(refused) {
                    "cleanup did not finish while a refused send was being contained"
                }
            }
        }
    }

    override fun onClose(ctx: PipelineHandlerContext) {
        transport.close()
    }
}
