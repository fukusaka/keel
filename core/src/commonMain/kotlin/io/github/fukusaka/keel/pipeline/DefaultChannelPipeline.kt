package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import io.github.fukusaka.keel.logging.warn

/**
 * Default [ChannelPipeline] implementation using a doubly-linked list of handler contexts.
 *
 * ```
 * head ↔ ctx1 ↔ ctx2 ↔ ... ↔ tail
 *
 * Inbound:  head → ctx1 → ctx2 → tail
 * Outbound: tail → ctx2 → ctx1 → head
 * ```
 *
 * [HeadHandler] connects the pipeline to the [IoTransport] (actual I/O).
 * [TailHandler] releases unreferenced messages and logs warnings.
 *
 * **Type chain validation**: [addLast], [addBefore], [addAfter], and [replace]
 * validate that adjacent [ChannelInboundHandler]s have compatible
 * [acceptedType]/[producedType] declarations. Validation is skipped when
 * either type is [Any] (the default).
 */
class DefaultChannelPipeline(
    override val channel: PipelinedChannel,
    transport: IoTransport,
    private val logger: Logger,
) : ChannelPipeline {

    private val head: DefaultContext = DefaultContext(this, "HEAD", HeadHandler(transport))
    private val tail: DefaultContext = DefaultContext(this, "TAIL", TailHandler(logger))

    init {
        head.next = tail
        tail.prev = head
    }

    // --- Composition ---

    override fun addFirst(name: String, handler: ChannelHandler): ChannelPipeline {
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val after = head.next!!
        validateInboundTypeChain(head.handler, handler, name)
        validateInboundTypeChain(handler, after.handler, after.name)
        insertBetween(head, newCtx, after)
        callHandlerAdded(newCtx)
        return this
    }

    override fun addLast(name: String, handler: ChannelHandler): ChannelPipeline {
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val before = tail.prev!!
        validateInboundTypeChain(before.handler, handler, name)
        validateInboundTypeChain(handler, tail.handler, "TAIL")
        insertBetween(before, newCtx, tail)
        callHandlerAdded(newCtx)
        return this
    }

    override fun addBefore(baseName: String, name: String, handler: ChannelHandler): ChannelPipeline {
        val base = getContext(baseName)
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val before = base.prev!!
        validateInboundTypeChain(before.handler, handler, name)
        validateInboundTypeChain(handler, base.handler, baseName)
        insertBetween(before, newCtx, base)
        callHandlerAdded(newCtx)
        return this
    }

    override fun addAfter(baseName: String, name: String, handler: ChannelHandler): ChannelPipeline {
        val base = getContext(baseName)
        checkDuplicateName(name)
        val newCtx = DefaultContext(this, name, handler)
        val after = base.next!!
        validateInboundTypeChain(base.handler, handler, name)
        validateInboundTypeChain(handler, after.handler, after.name)
        insertBetween(base, newCtx, after)
        callHandlerAdded(newCtx)
        return this
    }

    override fun remove(name: String): ChannelHandler {
        val ctx = getContext(name)
        val prev = ctx.prev!!
        val next = ctx.next!!
        validateInboundTypeChain(prev.handler, next.handler, next.name)
        prev.next = next
        next.prev = prev
        ctx.prev = null
        ctx.next = null
        callHandlerRemoved(ctx)
        return ctx.handler
    }

    override fun replace(oldName: String, newName: String, newHandler: ChannelHandler): ChannelHandler {
        val oldCtx = getContext(oldName)
        if (oldName != newName) checkDuplicateName(newName)
        val prev = oldCtx.prev!!
        val next = oldCtx.next!!
        validateInboundTypeChain(prev.handler, newHandler, newName)
        validateInboundTypeChain(newHandler, next.handler, next.name)
        val newCtx = DefaultContext(this, newName, newHandler)
        prev.next = newCtx
        newCtx.prev = prev
        newCtx.next = next
        next.prev = newCtx
        oldCtx.prev = null
        oldCtx.next = null
        callHandlerRemoved(oldCtx)
        callHandlerAdded(newCtx)
        return oldCtx.handler
    }

    override fun get(name: String): ChannelHandler? = findContext(name)?.handler

    override fun context(name: String): ChannelHandlerContext? = findContext(name)

    // --- Inbound entry ---

    override fun notifyActive(): ChannelPipeline {
        head.invokeOnActive()
        return this
    }

    override fun notifyRead(msg: Any): ChannelPipeline {
        head.invokeOnRead(msg)
        return this
    }

    override fun notifyReadComplete(): ChannelPipeline {
        head.invokeOnReadComplete()
        return this
    }

    override fun notifyInactive(): ChannelPipeline {
        head.invokeOnInactive()
        return this
    }

    override fun notifyError(cause: Throwable): ChannelPipeline {
        head.invokeOnError(cause)
        return this
    }

    // --- Outbound entry ---

    override fun requestWrite(msg: Any): ChannelPipeline {
        tail.invokeOnWrite(msg)
        return this
    }

    override fun requestFlush(): ChannelPipeline {
        tail.invokeOnFlush()
        return this
    }

    override fun requestClose(): ChannelPipeline {
        tail.invokeOnClose()
        return this
    }

    // --- Internal ---

    private fun insertBetween(before: DefaultContext, new: DefaultContext, after: DefaultContext) {
        before.next = new
        new.prev = before
        new.next = after
        after.prev = new
    }

    private fun findContext(name: String): DefaultContext? {
        var ctx = head.next
        while (ctx != null && ctx !== tail) {
            if (ctx.name == name) return ctx
            ctx = ctx.next
        }
        return null
    }

    private fun getContext(name: String): DefaultContext =
        findContext(name) ?: throw NoSuchElementException("Handler '$name' not found in pipeline")

    private fun checkDuplicateName(name: String) {
        require(findContext(name) == null) { "Duplicate handler name: '$name'" }
    }

    private fun callHandlerAdded(ctx: DefaultContext) {
        try {
            ctx.handler.handlerAdded(ctx)
        } catch (e: Throwable) {
            logger.error(e) { "handlerAdded() threw for '${ctx.name}'" }
        }
    }

    private fun callHandlerRemoved(ctx: DefaultContext) {
        try {
            ctx.handler.handlerRemoved(ctx)
        } catch (e: Throwable) {
            logger.error(e) { "handlerRemoved() threw for '${ctx.name}'" }
        }
    }

    /**
     * Validates inbound type chain between adjacent handlers.
     *
     * Skipped when either handler is not a [ChannelInboundHandler] or when
     * either type is [Any] (opt-out default).
     */
    private fun validateInboundTypeChain(
        prevHandler: ChannelHandler,
        nextHandler: ChannelHandler,
        nextName: String,
    ) {
        if (prevHandler !is ChannelInboundHandler) return
        if (nextHandler !is ChannelInboundHandler) return
        val produced = prevHandler.producedType
        val accepted = nextHandler.acceptedType
        if (produced == Any::class || accepted == Any::class) return
        // KMP limitation: no reflective supertype traversal (Class.isAssignableFrom
        // is JVM-only). Validate exact type match only. Subtype relationships
        // (e.g., HttpObject → HttpRequest) are not detected; handlers should
        // declare the exact type they produce/accept.
        if (produced != accepted) {
            throw PipelineTypeException(
                "Type mismatch in pipeline: '${nameOf(prevHandler)}' produces " +
                    "${produced.simpleName} but '$nextName' accepts ${accepted.simpleName}"
            )
        }
    }

    private fun nameOf(handler: ChannelHandler): String {
        var ctx: DefaultContext? = head
        while (ctx != null) {
            if (ctx.handler === handler) return ctx.name
            ctx = ctx.next
        }
        return handler::class.simpleName ?: "unknown"
    }

    // --- DefaultContext ---

    /**
     * A node in the doubly-linked list that forms the [DefaultChannelPipeline].
     *
     * Each context wraps a single [ChannelHandler] and provides the
     * [ChannelHandlerContext] interface for that handler to propagate
     * events to the next handler in the chain.
     *
     * **Inbound navigation** ([findNextInbound]): follows [next] pointers
     * from head toward tail, skipping non-[ChannelInboundHandler] nodes.
     *
     * **Outbound navigation** ([findPrevOutbound]): follows [prev] pointers
     * from tail toward head, skipping non-[ChannelOutboundHandler] nodes.
     *
     * **Invoke methods** (`invokeOn*`): wrap handler callbacks with try-catch
     * to prevent IoBuf leaks on exceptions. [invokeOnRead] releases the message
     * on exception; [invokeOnError] logs the secondary exception to avoid
     * infinite error propagation loops.
     */
    internal class DefaultContext(
        private val pipelineRef: DefaultChannelPipeline,
        override val name: String,
        override val handler: ChannelHandler,
    ) : ChannelHandlerContext {

        /** Previous node toward HEAD (outbound direction). Null when detached. */
        var prev: DefaultContext? = null

        /** Next node toward TAIL (inbound direction). Null when detached. */
        var next: DefaultContext? = null

        override val channel: PipelinedChannel get() = pipelineRef.channel
        override val pipeline: ChannelPipeline get() = pipelineRef
        override val allocator: BufferAllocator get() = channel.allocator

        // --- Inbound propagation ---

        override fun propagateActive() {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnActive()
        }

        override fun propagateRead(msg: Any) {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnRead(msg)
        }

        override fun propagateReadComplete() {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnReadComplete()
        }

        override fun propagateInactive() {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnInactive()
        }

        override fun propagateError(cause: Throwable) {
            val nextCtx = findNextInbound() ?: return
            nextCtx.invokeOnError(cause)
        }

        // --- Outbound propagation ---

        override fun propagateWrite(msg: Any) {
            val prevCtx = findPrevOutbound() ?: return
            prevCtx.invokeOnWrite(msg)
        }

        override fun propagateFlush() {
            val prevCtx = findPrevOutbound() ?: return
            prevCtx.invokeOnFlush()
        }

        override fun propagateClose() {
            val prevCtx = findPrevOutbound() ?: return
            prevCtx.invokeOnClose()
        }

        // --- Invoke with try-catch (leak prevention) ---

        internal fun invokeOnActive() {
            val h = handler
            if (h is ChannelInboundHandler) {
                try {
                    h.onActive(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateActive()
            }
        }

        internal fun invokeOnRead(msg: Any) {
            val h = handler
            if (h is ChannelInboundHandler) {
                try {
                    h.onRead(this, msg)
                } catch (e: Throwable) {
                    ReferenceCountUtil.safeRelease(msg)
                    propagateError(e)
                }
            } else {
                propagateRead(msg)
            }
        }

        internal fun invokeOnReadComplete() {
            val h = handler
            if (h is ChannelInboundHandler) {
                try {
                    h.onReadComplete(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateReadComplete()
            }
        }

        internal fun invokeOnInactive() {
            val h = handler
            if (h is ChannelInboundHandler) {
                try {
                    h.onInactive(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateInactive()
            }
        }

        internal fun invokeOnError(cause: Throwable) {
            val h = handler
            if (h is ChannelInboundHandler) {
                try {
                    h.onError(this, cause)
                } catch (e: Throwable) {
                    pipelineRef.logger.error(e) {
                        "onError() threw in '${name}' while handling: $cause"
                    }
                }
            } else {
                propagateError(cause)
            }
        }

        internal fun invokeOnWrite(msg: Any) {
            val h = handler
            if (h is ChannelOutboundHandler) {
                try {
                    h.onWrite(this, msg)
                } catch (e: Throwable) {
                    ReferenceCountUtil.safeRelease(msg)
                    propagateError(e)
                }
            } else {
                propagateWrite(msg)
            }
        }

        internal fun invokeOnFlush() {
            val h = handler
            if (h is ChannelOutboundHandler) {
                try {
                    h.onFlush(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateFlush()
            }
        }

        internal fun invokeOnClose() {
            val h = handler
            if (h is ChannelOutboundHandler) {
                try {
                    h.onClose(this)
                } catch (e: Throwable) {
                    propagateError(e)
                }
            } else {
                propagateClose()
            }
        }

        // --- Navigation ---

        private fun findNextInbound(): DefaultContext? {
            var ctx = next
            while (ctx != null) {
                if (ctx.handler is ChannelInboundHandler) return ctx
                ctx = ctx.next
            }
            return null
        }

        private fun findPrevOutbound(): DefaultContext? {
            var ctx = prev
            while (ctx != null) {
                if (ctx.handler is ChannelOutboundHandler) return ctx
                ctx = ctx.prev
            }
            return null
        }
    }
}
