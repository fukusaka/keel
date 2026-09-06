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

    /**
     * Called after the handler is added to a pipeline, and before the first
     * lifecycle event it is told about — on every engine, including the ones
     * that deliver the activation from inside the `add` call itself.
     */
    fun handlerAdded(ctx: PipelineHandlerContext) {}

    /**
     * Called after the handler is removed from a pipeline — at most once, and
     * the last thing the handler hears. This is where a handler releases what
     * it owns: the pipeline removes every handler at the end of its channel's
     * life, after the ending and outside any handler callback, so a release
     * here is neither skipped nor run inside the handler's own frame.
     *
     * A handler may remove itself from inside one of its own callbacks. The
     * walk that was passing through it goes on: propagating from its context
     * still reaches the next handler, because the context keeps its
     * neighbours after the removal. Nothing new is routed to it. A handler
     * may also be removed by another one, from a callback nested inside its
     * own — a handler that frees native state re-checks itself after each
     * propagation.
     */
    fun handlerRemoved(ctx: PipelineHandlerContext) {}
}

/**
 * Handles inbound I/O events: data arrival, connection lifecycle, and errors.
 *
 * All callbacks run on the EventLoop thread and MUST NOT block or suspend.
 * The default implementation of each callback propagates the event to the
 * next inbound handler via [PipelineHandlerContext.propagateRead] etc.
 *
 * **A throw does not stop a lifecycle event.** When [onActive] or [onInactive]
 * throws, the throw reaches the handlers below as [onError], and the event
 * reaches them too: the pipeline propagates it on the handler's behalf if the
 * handler had not, and adds nothing if it had. Every handler hears each
 * lifecycle event once, whatever the handlers above it did. A throw from
 * [onRead] is different — the message is released and reaches nobody below,
 * as in Netty.
 *
 * **A late handler is caught up alone.** A handler added after the chain was
 * activated (or ended) is told so from inside the `add` call; its default
 * propagation of that replayed event is a no-op, since the handlers below
 * already heard it. An event the handler raises from inside the replay — a
 * close, say — enters at the head and reaches everyone as usual. A handler
 * that holds the activation back to propagate it later — a gate, as Netty's
 * `SslHandler` is — is not supported: a handler added below it meanwhile is
 * caught up on its own, and the gate's later propagation stops at it, short
 * of the handlers further below. A handler added with `addFirst` / `addLast`
 * once the channel's life has ended is served a complete lifecycle in that
 * one call: `handlerAdded`, the ending, `handlerRemoved`; the pipeline is
 * empty by then, so an add or a `replace` naming an existing handler finds
 * none and throws as it always did.
 *
 * **Each lifecycle event arrives at most once, and the ending may come first.**
 * A handler that joins a connection already over hears [onInactive] without
 * an [onActive] before it, so what [onInactive] undoes must tolerate never
 * having been done. No activation follows an ending, and no [onReadClosed]
 * does either. [onInactive] says the connection is over as far as this
 * pipeline is concerned; it does not say the transport is closed yet — on the
 * owning loop it is still open — and whether it precedes or follows
 * [OutboundHandler.onClose] is only fixed for the channel's own close on its
 * loop. A handler passes the ending on from [onInactive] and does not raise
 * one of its own from another callback: the ending is the pipeline's to
 * deliver, and so is the peer's end of file — a handler that learns of one
 * inside its own protocol (a TLS close_notify) passes on [onReadClosed], not
 * the ending.
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

    /**
     * Called when the peer has closed its side for writing: no read follows,
     * and the connection is still open and writable, so a handler that
     * answers from within this call can still answer — the half-close a
     * request/response peer performs after its last request. Netty raises
     * `ChannelInputShutdownEvent` for the same fact.
     *
     * At most once per handler, after [onActive] and before [onInactive]; a
     * handler added after the event was delivered hears it as a replay, whether
     * the channel or a handler's own [Pipeline.notifyReadClosed] delivered
     * it — a handler that passed it on with
     * [PipelineHandlerContext.propagateReadClosed] instead delivered nothing
     * and is not replayed. Not delivered once the
     * ending was, nor once the transport is gone — then only the ending is.
     * What a handler releases here must be what the read side alone held; the
     * connection's own resources wait for [onInactive] and
     * [PipelineHandler.handlerRemoved]. When the transport reported it, a
     * Pipeline-mode channel closes itself right after this reaches the
     * chain, so the ending follows at once there: an answer must be written
     * and flushed from within this call — the close delivers what the socket
     * takes at once — and a handler that answers later — keel's own
     * HTTP servers answer from a coroutine, which their ending cancels —
     * does not answer a peer that half-closed. A handler that raises it from
     * inside the chain with [Pipeline.notifyReadClosed] (a TLS close_notify)
     * gets the same close; one that only tells the handlers below it passes
     * the event on with [PipelineHandlerContext.propagateReadClosed], which
     * leaves the connection open. In
     * Coroutine mode the caller reads what was queued, gets `-1`, and closes
     * when it is done.
     */
    fun onReadClosed(ctx: PipelineHandlerContext) {
        ctx.propagateReadClosed()
    }

    /**
     * Called when the connection has ended for this pipeline: nothing more
     * arrives, nothing more can be sent. The peer's end of file alone is not
     * this — that is [onReadClosed], and the connection is still writable
     * after it.
     *
     * At most once per handler, and possibly the first thing it hears: the
     * pipeline delivers the ending once, and a handler above that throws or
     * removes itself does not keep it from the handlers below (see the class
     * KDoc). Every channel's life ends with it — a close a handler started
     * from its own context included — followed by [PipelineHandler.handlerRemoved].
     */
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
 *
 * **Each handler hears its close at most once.** The walk runs from the tail
 * toward the head through the handlers' own propagation; a handler that does
 * not pass the close on ends that walk, as in Netty, and a later walk passes
 * it over rather than asking it again. A handler that throws from [onClose]
 * does not keep the close from the handlers above it — the pipeline
 * propagates it on the handler's behalf. A close a handler asks for from
 * inside its own [onClose], through the channel or [Pipeline.requestClose],
 * is served after the running walk, as a new walk that skips whoever already
 * heard it. Releasing what the handler owns belongs in
 * [PipelineHandler.handlerRemoved], which the end of the channel's life runs
 * for every handler whether or not the walk reached it; [onClose] is for the
 * protocol's farewell, and must not free what the handler's own inbound
 * callback may still be using, since the walk can run inside it.
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

    /**
     * Called when a close is requested — at most once per handler. See the
     * interface KDoc for what a throw or a re-entrant close does here. A close
     * a handler initiates from its own context
     * ([PipelineHandlerContext.propagateClose] outside any walk) is a walk
     * from there toward the head; the handlers on the tail side of it hear
     * the ending and `handlerRemoved`, and their close only if a close of the
     * channel is asked for before the end of life removes them. There is no
     * close replay: a handler installed later checks the channel's `isOpen`
     * in `handlerAdded`. Whether the transport is still open inside this
     * callback depends on the thread the close came from; do not read it as
     * a stable signal.
     */
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
