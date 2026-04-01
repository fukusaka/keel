package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator

/**
 * Context for a [ChannelHandler] within a [ChannelPipeline].
 *
 * Provides access to the pipeline, channel, and buffer allocator, as well as
 * methods to propagate events to the next handler in the chain.
 *
 * **Inbound propagation** (`propagateRead`, `propagateActive`, etc.) flows
 * from head to tail — each call invokes the next [ChannelInboundHandler].
 *
 * **Outbound propagation** (`propagateWrite`, `propagateFlush`, etc.) flows
 * from tail to head — each call invokes the next [ChannelOutboundHandler].
 *
 * All methods must be called on the EventLoop thread.
 */
interface ChannelHandlerContext {

    /** The channel this context belongs to. */
    val channel: PipelinedChannel

    /** The pipeline this context belongs to. */
    val pipeline: ChannelPipeline

    /** The name of the handler in the pipeline. */
    val name: String

    /** The handler associated with this context. */
    val handler: ChannelHandler

    /** The buffer allocator for this channel. */
    val allocator: BufferAllocator

    // --- Inbound propagation: next inbound handler ---

    /** Propagates a channel-active event to the next inbound handler. */
    fun propagateActive()

    /** Propagates a read event to the next inbound handler. */
    fun propagateRead(msg: Any)

    /** Propagates a read-complete event to the next inbound handler. */
    fun propagateReadComplete()

    /** Propagates a channel-inactive event to the next inbound handler. */
    fun propagateInactive()

    /** Propagates an error to the next inbound handler. */
    fun propagateError(cause: Throwable)

    // --- Outbound propagation: next outbound handler ---

    /** Propagates a write request to the next outbound handler. */
    fun propagateWrite(msg: Any)

    /** Propagates a flush request to the next outbound handler. */
    fun propagateFlush()

    /** Propagates a close request to the next outbound handler. */
    fun propagateClose()

    /** Convenience: propagateWrite + propagateFlush. */
    fun propagateWriteAndFlush(msg: Any) {
        propagateWrite(msg)
        propagateFlush()
    }
}
