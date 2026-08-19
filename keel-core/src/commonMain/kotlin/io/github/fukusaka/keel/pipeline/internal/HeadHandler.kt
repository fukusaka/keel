package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.RefusedWriteException
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
        } catch (@Suppress("SwallowedException") refused: RefusedWriteException) {
            // Reported or named, never converted. A refusal has one
            // construction site and every raise passes through the
            // transport's flush funnel: the first on a live connection is
            // delivered to this pipeline -- riders included, as suppressed
            // causes -- before the connection ends, so converting the
            // rethrow would tell the same handlers the same instance twice.
            // One the funnel stays quiet about (the caller was closing, or
            // the connection's reason is an earlier refusal) is warn-logged
            // there when it carries a failed cleanup, so swallowing it here
            // silences nothing unnamed. Anything else a flush throws still
            // propagates to the generic catch, which is where a transport
            // fault becomes a pipeline error.
        }
    }

    override fun onClose(ctx: PipelineHandlerContext) {
        transport.close()
    }
}
