package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator

/**
 * Wrapper around [ChannelHandlerContext] that detects propagation calls.
 *
 * Used by [TypedChannelInboundHandler] to determine whether the handler
 * forwarded the message to the next handler. If [propagateRead] is called,
 * [onPropagate] fires, signaling that auto-release should be skipped
 * (the next handler now owns the message).
 */
internal class PropagateTrackingContext(
    private val delegate: ChannelHandlerContext,
    private val onPropagate: () -> Unit,
) : ChannelHandlerContext {

    override val channel: PipelinedChannel get() = delegate.channel
    override val pipeline: ChannelPipeline get() = delegate.pipeline
    override val name: String get() = delegate.name
    override val handler: ChannelHandler get() = delegate.handler
    override val allocator: BufferAllocator get() = delegate.allocator

    override fun propagateActive() = delegate.propagateActive()

    override fun propagateRead(msg: Any) {
        onPropagate()
        delegate.propagateRead(msg)
    }

    override fun propagateReadComplete() = delegate.propagateReadComplete()
    override fun propagateInactive() = delegate.propagateInactive()
    override fun propagateError(cause: Throwable) = delegate.propagateError(cause)
    override fun propagateUserEvent(event: Any) = delegate.propagateUserEvent(event)
    override fun propagateWritabilityChanged(isWritable: Boolean) = delegate.propagateWritabilityChanged(isWritable)

    override fun propagateWrite(msg: Any) = delegate.propagateWrite(msg)
    override fun propagateFlush() = delegate.propagateFlush()
    override fun propagateClose() = delegate.propagateClose()
}
