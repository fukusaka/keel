package io.github.fukusaka.keel.pipeline

import kotlin.reflect.KClass

/**
 * Type-safe inbound handler that filters messages by type.
 *
 * Messages matching [type] are dispatched to [onReadTyped]. Non-matching
 * messages are propagated to the next handler unchanged.
 *
 * **Auto-release**: when [autoRelease] is true (default), the message is
 * released after [onReadTyped] returns — unless the handler propagated it
 * to the next handler (detected via [PropagateTrackingContext]). This
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
