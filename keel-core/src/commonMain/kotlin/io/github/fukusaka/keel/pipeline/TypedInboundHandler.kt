package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.pipeline.internal.ReferenceCountUtil
import kotlin.reflect.KClass

/**
 * Type-safe inbound handler that filters messages by type.
 *
 * Messages matching [type] are dispatched to [onReadTyped]. Non-matching
 * messages are propagated to the next handler unchanged.
 *
 * **Auto-release**: when [autoRelease] is true (default), the message is
 * released after [onReadTyped] returns — unless the handler propagated it
 * to the next handler (detected via an internal context wrapper). This
 * prevents use-after-free when a handler both forwards and auto-releases.
 *
 * **Pipeline type validation**: [acceptedType] is automatically set to [type],
 * enabling construction-time type chain validation.
 *
 * ```kotlin
 * class MyHandler : TypedInboundHandler<HttpRequest>(HttpRequest::class) {
 *     override fun onReadTyped(ctx: PipelineHandlerContext, msg: HttpRequest) {
 *         ctx.propagateWriteAndFlush(buildResponse(msg))
 *     }
 * }
 * ```
 */
abstract class TypedInboundHandler<I : Any>(
    private val type: KClass<I>,
    private val autoRelease: Boolean = true,
) : InboundHandler {

    override val acceptedType: KClass<*> get() = type

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (type.isInstance(msg)) {
            @Suppress("UNCHECKED_CAST")
            val castedMsg = msg as I
            var propagated = false
            val trackingCtx = PropagateTrackingContext(ctx) { propagated = true }
            try {
                onReadTyped(trackingCtx, castedMsg)
            } finally {
                if (autoRelease && !propagated) {
                    ReferenceCountUtil.safeRelease(msg)
                }
            }
        } else {
            ctx.propagateRead(msg)
        }
    }

    /**
     * Called when a message of type [I] is received.
     *
     * If [autoRelease] is true, the message is released after this method
     * returns (unless propagated via [PipelineHandlerContext.propagateRead]).
     */
    abstract fun onReadTyped(ctx: PipelineHandlerContext, msg: I)
}

/**
 * Creates a [TypedInboundHandler] from a lambda.
 *
 * Uses Kotlin's reified type parameters to automatically infer the
 * message type — no explicit [KClass] parameter needed.
 *
 * ```kotlin
 * pipeline.addLast("handler", typedHandler<HttpRequest> { ctx, msg ->
 *     ctx.propagateWriteAndFlush(buildResponse(msg))
 * })
 * ```
 */
inline fun <reified I : Any> typedHandler(
    crossinline block: (PipelineHandlerContext, I) -> Unit,
): TypedInboundHandler<I> = object : TypedInboundHandler<I>(I::class) {
    override fun onReadTyped(ctx: PipelineHandlerContext, msg: I) = block(ctx, msg)
}

/**
 * Wrapper around [PipelineHandlerContext] that detects propagation calls.
 *
 * Used by [TypedInboundHandler] to determine whether the handler forwarded
 * the message to the next handler. If [propagateRead] is called, [onPropagate]
 * fires, signaling that auto-release should be skipped (the next handler now
 * owns the message).
 */
private class PropagateTrackingContext(
    private val delegate: PipelineHandlerContext,
    private val onPropagate: () -> Unit,
) : PipelineHandlerContext {

    override val channel: PipelinedChannel get() = delegate.channel
    override val pipeline: Pipeline get() = delegate.pipeline
    override val name: String get() = delegate.name
    override val handler: PipelineHandler get() = delegate.handler
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
