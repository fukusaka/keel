package io.github.fukusaka.keel.pipeline

import kotlin.reflect.KClass

/**
 * Base marker for all pipeline handlers.
 *
 * A handler intercepts I/O events flowing through a [ChannelPipeline].
 * Implement [ChannelInboundHandler] for inbound events (data received,
 * connection lifecycle) or [ChannelOutboundHandler] for outbound operations
 * (write, flush, close).
 */
interface ChannelHandler {

    /** Called after the handler is added to a pipeline. */
    fun handlerAdded(ctx: ChannelHandlerContext) {}

    /** Called after the handler is removed from a pipeline. */
    fun handlerRemoved(ctx: ChannelHandlerContext) {}
}

/**
 * Handles inbound I/O events: data arrival, connection lifecycle, and errors.
 *
 * All callbacks run on the EventLoop thread and MUST NOT block or suspend.
 * The default implementation of each callback propagates the event to the
 * next inbound handler via [ChannelHandlerContext.propagateRead] etc.
 *
 * [acceptedType] and [producedType] declare the message types this handler
 * consumes and produces. The pipeline validates type chain consistency at
 * construction time ([ChannelPipeline.addLast] etc.), catching mismatches
 * before any message flows. Handlers that do not declare types default to
 * [Any] and skip validation.
 */
interface ChannelInboundHandler : ChannelHandler {

    /**
     * The message type this handler accepts in [onRead].
     * Used for pipeline type chain validation at construction time.
     * Default [Any] skips validation (opt-in).
     */
    val acceptedType: KClass<*> get() = Any::class

    /**
     * The message type this handler produces via [ChannelHandlerContext.propagateRead].
     * Used for pipeline type chain validation at construction time.
     * Default [Any] skips validation (opt-in).
     */
    val producedType: KClass<*> get() = Any::class

    /** Called when the channel becomes active (connected). */
    fun onActive(ctx: ChannelHandlerContext) {
        ctx.propagateActive()
    }

    /** Called when data is received. */
    fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        ctx.propagateRead(msg)
    }

    /** Called when a batch of reads is complete. */
    fun onReadComplete(ctx: ChannelHandlerContext) {
        ctx.propagateReadComplete()
    }

    /** Called when the channel becomes inactive (disconnected). */
    fun onInactive(ctx: ChannelHandlerContext) {
        ctx.propagateInactive()
    }

    /** Called when an error occurs in the pipeline. */
    fun onError(ctx: ChannelHandlerContext, cause: Throwable) {
        ctx.propagateError(cause)
    }
}

/**
 * Handles outbound I/O operations: write, flush, and close.
 *
 * All callbacks run on the EventLoop thread and MUST NOT block or suspend.
 * The default implementation propagates each operation to the next outbound
 * handler via [ChannelHandlerContext.propagateWrite] etc.
 */
interface ChannelOutboundHandler : ChannelHandler {

    /** Called when a write is requested. */
    fun onWrite(ctx: ChannelHandlerContext, msg: Any) {
        ctx.propagateWrite(msg)
    }

    /** Called when a flush is requested. */
    fun onFlush(ctx: ChannelHandlerContext) {
        ctx.propagateFlush()
    }

    /** Called when a close is requested. */
    fun onClose(ctx: ChannelHandlerContext) {
        ctx.propagateClose()
    }
}

/**
 * Combined handler implementing both inbound and outbound.
 *
 * Useful for codecs that transform messages in both directions
 * (e.g., HTTP request decoder + response encoder).
 */
interface ChannelDuplexHandler : ChannelInboundHandler, ChannelOutboundHandler
