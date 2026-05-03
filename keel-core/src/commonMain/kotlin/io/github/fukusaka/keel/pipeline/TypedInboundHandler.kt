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
 * released after [onReadTyped] returns — unless the handler propagated the
 * EXACT SAME object to the next handler (identity check, not equality).
 * A transforming handler (e.g. [IoBuf] → [WsFrame]) propagates a different
 * object; the original input is still auto-released. This prevents both
 * use-after-free (when the original is forwarded) and memory leaks (when a
 * transformed replacement is forwarded instead).
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
            // Track whether the ORIGINAL message object was forwarded.
            // A handler that transforms the input (e.g. IoBuf → WsFrame) sets
            // propagated=true by propagating the new object, not the original,
            // so we must compare identity rather than just checking that any
            // propagateRead call happened.
            var originalPropagated = false
            val trackingCtx = PropagateTrackingContext(ctx) { propagatedMsg ->
                if (propagatedMsg === castedMsg) originalPropagated = true
            }
            try {
                onReadTyped(trackingCtx, castedMsg)
            } finally {
                if (autoRelease && !originalPropagated) {
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
     * returns — unless this EXACT message object was propagated via
     * [PipelineHandlerContext.propagateRead]. Transforming handlers that
     * produce a different output object and propagate that instead must
     * NOT retain the original [msg] after this method returns; the
     * auto-release mechanism will free it.
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
 * the ORIGINAL input message to the next handler. [onPropagate] receives the
 * message object passed to [propagateRead] so the caller can compare identity
 * against the original — a handler that transforms its input (e.g.
 * [IoBuf] → [WsFrame]) propagates a different object and the original must
 * still be auto-released.
 */
private class PropagateTrackingContext(
    private val delegate: PipelineHandlerContext,
    private val onPropagate: (Any) -> Unit,
) : PipelineHandlerContext {

    override val channel: PipelinedChannel get() = delegate.channel
    override val pipeline: Pipeline get() = delegate.pipeline
    override val name: String get() = delegate.name
    override val handler: PipelineHandler get() = delegate.handler
    override val allocator: BufferAllocator get() = delegate.allocator

    override fun propagateActive() = delegate.propagateActive()

    override fun propagateRead(msg: Any) {
        onPropagate(msg)
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
