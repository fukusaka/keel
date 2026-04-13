package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator

/**
 * Context for a [PipelineHandler] within a [Pipeline].
 *
 * Provides access to the pipeline, channel, and buffer allocator, as well as
 * methods to propagate events to the next handler in the chain.
 *
 * **Inbound propagation** (`propagateRead`, `propagateActive`, etc.) flows
 * from head to tail — each call invokes the next [InboundHandler].
 *
 * **Outbound propagation** (`propagateWrite`, `propagateFlush`, etc.) flows
 * from tail to head — each call invokes the next [OutboundHandler].
 *
 * All methods must be called on the EventLoop thread.
 */
interface PipelineHandlerContext {

    /** The channel this context belongs to. */
    val channel: PipelinedChannel

    /** The pipeline this context belongs to. */
    val pipeline: Pipeline

    /** The name of the handler in the pipeline. */
    val name: String

    /** The handler associated with this context. */
    val handler: PipelineHandler

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

    /** Propagates a user event to the next inbound handler. */
    fun propagateUserEvent(event: Any)

    /** Propagates a writability change to the next inbound handler. */
    fun propagateWritabilityChanged(isWritable: Boolean)

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
