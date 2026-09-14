package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.Releasable
import io.github.fukusaka.keel.pipeline.internal.ReferenceCountUtil
import kotlin.jvm.JvmField
import kotlin.reflect.KClass

/**
 * Type-safe inbound handler that filters messages by type.
 *
 * Messages matching [type] are dispatched to [onReadTyped]. Non-matching
 * messages are propagated to the next handler unchanged.
 *
 * **Auto-release**: when [autoRelease] is true (default), the message is
 * released after [onReadTyped] returns — unless the handler propagated the
 * same object to the next handler, or an object that shares its ownership
 * ([Releasable.sharesOwnershipWith]), such as a copy holding the same pooled
 * resources.
 * A transforming handler (e.g. [IoBuf] → [WsFrame]) propagates a different
 * object; the original input is still auto-released. This prevents both
 * use-after-free (when the original is forwarded) and memory leaks (when a
 * transformed replacement is forwarded instead). A message that owns pooled
 * resources — a decoded HTTP head, for instance — is not valid after the
 * callback returns: keep one beyond it through the ownership transfer its
 * type offers (such as `detach()`), or construct the handler with
 * `autoRelease = false` and release it yourself.
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

    // Reusable per-handler scratch wrapper so the read hot path no longer
    // allocates a fresh PropagateTrackingContext + capture lambda +
    // Ref$BooleanRef per message. Handlers are per-pipeline by keel
    // convention (constructed inside each connection's pipeline
    // initialiser), so the cache is EventLoop-confined and needs no
    // synchronisation. Allocated lazily on the first message that matches
    // [type]; refreshed on every call so a handler instance moved between
    // pipelines still tracks the current delegate.
    private var trackingCtx: PropagateTrackingContext? = null

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (type.isInstance(msg)) {
            @Suppress("UNCHECKED_CAST")
            val castedMsg = msg as I
            val tracking = trackingCtx ?: PropagateTrackingContext(ctx).also { trackingCtx = it }
            tracking.delegate = ctx
            // Track whether ownership of the input was handed on: the same
            // object, or one sharing its resources. A handler that transforms
            // the input (e.g. IoBuf → WsFrame) propagates an object that owns
            // something else, and the input must still be auto-released.
            tracking.original = castedMsg
            tracking.handedOn = false
            try {
                onReadTyped(tracking, castedMsg)
            } finally {
                tracking.original = null
                if (autoRelease && !tracking.handedOn) {
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
 * Reusable wrapper around [PipelineHandlerContext] that records the most
 * recently propagated `read` message.
 *
 * Used by [TypedInboundHandler] to determine whether the handler handed
 * ownership of its input on: [handedOn] is set when any message it propagates
 * is the [original] or shares its ownership. A handler that transforms its
 * input (e.g. [IoBuf] → [WsFrame]) propagates an object that owns something
 * else, and the original must still be auto-released.
 *
 * [delegate], [original] and [handedOn] are `var` because a single
 * [TypedInboundHandler] reuses one instance across every read; the
 * enclosing handler is per-pipeline (EventLoop-confined), so mutating these
 * fields is safe without synchronisation.
 */
private class PropagateTrackingContext(
    @JvmField var delegate: PipelineHandlerContext,
) : PipelineHandlerContext {

    @JvmField var original: Any? = null

    @JvmField var handedOn: Boolean = false

    override val channel: PipelinedChannel get() = delegate.channel
    override val pipeline: Pipeline get() = delegate.pipeline
    override val name: String get() = delegate.name
    override val handler: PipelineHandler get() = delegate.handler
    override val allocator: BufferAllocator get() = delegate.allocator

    override fun propagateActive() = delegate.propagateActive()

    override fun propagateRead(msg: Any) {
        if (!handedOn) {
            val input = original
            handedOn = msg === input || (input is Releasable && input.sharesOwnershipWith(msg))
        }
        delegate.propagateRead(msg)
    }

    override fun propagateReadComplete() = delegate.propagateReadComplete()

    override fun propagateFlushComplete() = delegate.propagateFlushComplete()
    override fun propagateReadClosed(): Boolean = delegate.propagateReadClosed()
    override fun propagateInactive() = delegate.propagateInactive()
    override fun propagateError(cause: Throwable) = delegate.propagateError(cause)
    override fun propagateUserEvent(event: Any) = delegate.propagateUserEvent(event)
    override fun propagateWritabilityChanged(isWritable: Boolean) = delegate.propagateWritabilityChanged(isWritable)

    override fun propagateWrite(msg: Any) = delegate.propagateWrite(msg)
    override fun propagateFlush() = delegate.propagateFlush()
    override fun propagateClose() = delegate.propagateClose()
}
