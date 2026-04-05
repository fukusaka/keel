package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf

/**
 * The head of the pipeline — connects inbound/outbound to the [IoTransport].
 *
 * **Inbound**: propagates events to the next handler (acts as the entry point).
 * **Outbound**: terminates the chain by delegating to the transport.
 *
 * HeadHandler implements both [ChannelInboundHandler] and [ChannelOutboundHandler]
 * so it participates in both directions of the pipeline.
 */
internal class HeadHandler(
    private val transport: IoTransport,
) : ChannelDuplexHandler {

    // --- Inbound: pass through to next handler ---

    // Default implementations from ChannelInboundHandler propagate automatically.
    // HeadHandler does not transform inbound messages.

    // --- Outbound: terminate at transport ---

    override fun onWrite(ctx: ChannelHandlerContext, msg: Any) {
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

    override fun onFlush(ctx: ChannelHandlerContext) {
        transport.flush()
    }

    override fun onClose(ctx: ChannelHandlerContext) {
        transport.close()
    }
}
