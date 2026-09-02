package io.github.fukusaka.keel.pipeline

import kotlin.reflect.KClass

/**
 * Base marker for all pipeline handlers.
 *
 * A handler intercepts I/O events flowing through a [Pipeline].
 * Implement [InboundHandler] for inbound events (data received,
 * connection lifecycle) or [OutboundHandler] for outbound operations
 * (write, flush, close).
 */
interface PipelineHandler {

    /** Called after the handler is added to a pipeline. */
    fun handlerAdded(ctx: PipelineHandlerContext) {}

    /** Called after the handler is removed from a pipeline. */
    fun handlerRemoved(ctx: PipelineHandlerContext) {}
}

/**
 * Handles inbound I/O events: data arrival, connection lifecycle, and errors.
 *
 * All callbacks run on the EventLoop thread and MUST NOT block or suspend.
 * The default implementation of each callback propagates the event to the
 * next inbound handler via [PipelineHandlerContext.propagateRead] etc.
 *
 * [acceptedType] and [producedType] declare the message types this handler
 * consumes and produces. The pipeline validates type chain consistency at
 * construction time ([Pipeline.addLast] etc.), catching mismatches
 * before any message flows. Handlers that do not declare types default to
 * [Any] and skip validation.
 */
interface InboundHandler : PipelineHandler {

    /**
     * The message type this handler accepts in [onRead].
     * Used for pipeline type chain validation at construction time.
     * Default [Any] skips validation (opt-in).
     */
    val acceptedType: KClass<*> get() = Any::class

    /**
     * The message type this handler produces via [PipelineHandlerContext.propagateRead].
     * Used for pipeline type chain validation at construction time.
     * Default [Any] skips validation (opt-in).
     */
    val producedType: KClass<*> get() = Any::class

    /** Called when the channel becomes active (connected). */
    fun onActive(ctx: PipelineHandlerContext) {
        ctx.propagateActive()
    }

    /** Called when data is received. */
    fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        ctx.propagateRead(msg)
    }

    /** Called when a batch of reads is complete. */
    fun onReadComplete(ctx: PipelineHandlerContext) {
        ctx.propagateReadComplete()
    }

    /**
     * Called when the transport considers a flush this pipeline asked for
     * finished — which is not the same as the peer having the bytes, and on
     * some engines not the same as the kernel having them.
     *
     * Where a handler releases what it was holding for those bytes, or lets a
     * producer it had paused continue. The default passes it on.
     *
     * **Do not flush from here.** A transport whose flush drains in place
     * answers before it returns, so a handler that writes the next chunk and
     * flushes it is calling itself: measured at 1206 frames before a stack
     * overflow, which this pipeline then catches and reports as an ordinary
     * error while the chunks that were never written go unmentioned. A
     * transport that folds the reentrant episode instead — the readiness
     * engines do — reports no second completion at all, and the same handler
     * stalls rather than overflowing. Send the next chunk from the writability
     * signal, which exists for it.
     *
     * Best-effort besides: it can report a flush that wrote nothing, it can
     * arrive synchronously from inside the flush that caused it, a transport
     * that cannot tell when a write landed does not send it, and the count
     * does not match the handler's own flushes — each engine batches on its
     * own terms. Do not read it as an acknowledgement from anyone.
     */
    fun onFlushComplete(ctx: PipelineHandlerContext) {
        ctx.propagateFlushComplete()
    }

    /** Called when the channel becomes inactive (disconnected). */
    fun onInactive(ctx: PipelineHandlerContext) {
        ctx.propagateInactive()
    }

    /** Called when an error occurs in the pipeline. */
    fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
        ctx.propagateError(cause)
    }

    /**
     * Called when a user-defined event is fired in the pipeline.
     *
     * User events flow inbound (HEAD → TAIL), like other inbound events.
     * Handlers that are interested in a specific event type should check
     * `event` and either handle it or propagate to the next handler.
     *
     * Example: a TLS handler fires a handshake-complete event so
     * downstream handlers can act on it (e.g., start sending data).
     */
    fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
        ctx.propagateUserEvent(event)
    }

    /**
     * Called when the channel's write backpressure state changes.
     *
     * [isWritable] is false when pending write bytes exceed the high water mark,
     * and true when they drop below the low water mark. Handlers should pause
     * writing when false and resume when true.
     *
     * Flows inbound (HEAD → TAIL), like other inbound events.
     */
    fun onWritabilityChanged(ctx: PipelineHandlerContext, isWritable: Boolean) {
        ctx.propagateWritabilityChanged(isWritable)
    }
}

/**
 * Handles outbound I/O operations: write, flush, and close.
 *
 * All callbacks run on the EventLoop thread and MUST NOT block or suspend.
 * The default implementation propagates each operation to the next outbound
 * handler via [PipelineHandlerContext.propagateWrite] etc.
 */
interface OutboundHandler : PipelineHandler {

    /** Called when a write is requested. */
    fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        ctx.propagateWrite(msg)
    }

    /** Called when a flush is requested. */
    fun onFlush(ctx: PipelineHandlerContext) {
        ctx.propagateFlush()
    }

    /** Called when a close is requested. */
    fun onClose(ctx: PipelineHandlerContext) {
        ctx.propagateClose()
    }
}

/**
 * Combined handler implementing both inbound and outbound.
 *
 * Useful for codecs that transform messages in both directions
 * (e.g., HTTP request decoder + response encoder).
 */
interface DuplexHandler : InboundHandler, OutboundHandler
