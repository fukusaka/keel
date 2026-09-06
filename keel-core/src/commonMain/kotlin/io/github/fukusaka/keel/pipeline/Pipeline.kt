package io.github.fukusaka.keel.pipeline

/**
 * An ordered chain of [PipelineHandler]s that process inbound and outbound I/O events.
 *
 * **Inbound events** (data received, connection lifecycle) flow from head to tail.
 * **Outbound operations** (write, flush, close) flow from tail to head.
 *
 * ```
 * HEAD ↔ [Decoder] ↔ [Encoder] ↔ [UserHandler] ↔ TAIL
 *
 * Inbound:  HEAD → Decoder → UserHandler → TAIL
 * Outbound: TAIL → Encoder → HEAD
 * ```
 *
 * **Type chain validation**: when a handler declares [InboundHandler.acceptedType]
 * and [InboundHandler.producedType], the pipeline validates that adjacent handlers
 * have compatible types at construction time ([addLast], [replace], etc.). This catches
 * pipeline configuration errors before any message flows.
 *
 * **Threading**: handler callbacks run on the EventLoop thread, so a handler
 * calling back into the pipeline is already there. The outbound entry points
 * ([requestWrite], [requestFlush], [requestClose]) are also safe to call from
 * another thread — they dispatch onto the EventLoop rather than touch the
 * transport's state from the caller's thread — at the cost of the work
 * happening after the call returns. Everything else, chain mutation included,
 * must be called on the EventLoop thread.
 */
interface Pipeline {

    /** The channel this pipeline belongs to. */
    val channel: PipelinedChannel

    /**
     * True when no user handlers are installed — only the internal HEAD
     * and TAIL sentinels are present.
     *
     * A non-empty pipeline means the channel has a Pipeline-mode consumer
     * (the installed handlers); an empty one means either an unused
     * channel or a Coroutine-mode channel before its lazy
     * [SuspendBridgeHandler] is wired.
     */
    val isEmpty: Boolean

    // --- Pipeline composition ---

    /** Adds a handler at the beginning of the pipeline (after HEAD). */
    fun addFirst(name: String, handler: PipelineHandler): Pipeline

    /** Adds a handler at the end of the pipeline (before TAIL). */
    fun addLast(name: String, handler: PipelineHandler): Pipeline

    /** Adds a handler before the handler named [baseName]. */
    fun addBefore(baseName: String, name: String, handler: PipelineHandler): Pipeline

    /** Adds a handler after the handler named [baseName]. */
    fun addAfter(baseName: String, name: String, handler: PipelineHandler): Pipeline

    /**
     * Removes the handler with the given [name] and returns it.
     *
     * Safe from inside the handler's own callbacks: the walk that is passing
     * through it continues from its context, which keeps its neighbours.
     */
    fun remove(name: String): PipelineHandler

    /**
     * Replaces the handler named [oldName] with [newHandler] and returns the old handler.
     *
     * The replacement is a new context: it is caught up like a late-added
     * handler, and a walk passing through the old handler's position continues
     * to it — a close walk in flight reaches the replacement, not the handler
     * it replaced, which hears only `handlerRemoved`. The old context keeps
     * pointing at its replacement, so what the replaced handler forwards after
     * replacing itself arrives there.
     */
    fun replace(oldName: String, newName: String, newHandler: PipelineHandler): PipelineHandler

    /** Returns the handler with the given [name], or null. */
    fun get(name: String): PipelineHandler?

    /** Returns the context for the handler named [name], or null. */
    fun context(name: String): PipelineHandlerContext?

    // --- Inbound: engine notifies the pipeline ---

    /** Notifies the pipeline that the channel is now active. */
    fun notifyActive(): Pipeline

    /** Notifies the pipeline that data has been received. */
    fun notifyRead(msg: Any): Pipeline

    /** Notifies the pipeline that a batch of reads is complete. */
    fun notifyReadComplete(): Pipeline

    /**
     * Notifies the pipeline that the transport considers a flush finished.
     *
     * Not that the peer has the bytes, and on some engines not even that the
     * kernel does: the Node transport raises one whenever its write returns,
     * including the backpressured return that leaves the bytes in Node's own
     * buffer.
     *
     * The answer to [requestFlush], which is a request and returns nothing.
     * A handler that wants to know when its bytes are gone — to release what
     * it was holding for them, to send the next chunk, to let a producer
     * continue — has this and nothing else: the transport's own completion is
     * a callback with one slot, and the pipeline is what shares it out.
     *
     * Best-effort, like [notifyReadComplete]. A transport may report a flush
     * that wrote nothing, may report synchronously from inside the flush
     * itself, and one that cannot tell when a write landed need not report at
     * all. Nor do the counts line up with a handler's own flushes: the
     * readiness engines report per drained episode and fold a reentrant one,
     * io-uring reports per completion chain, nio per scheduled tick. A handler
     * that needs certainty waits on the channel instead.
     *
     * "Need not report" is not hypothetical. The nio engine reports from the
     * tick its coalescing schedules, so with coalescing turned off it drains
     * in place and reports nothing — measured, one flush and no completion.
     * io-uring's synchronous fast path returns the moment the whole write went
     * out, without reporting either. A handler must work without the signal,
     * and treat it as an opportunity rather than a turn it is owed.
     */
    fun notifyFlushComplete(): Pipeline

    /**
     * Notifies the pipeline that the peer has closed its side for writing.
     *
     * The read side is over — no read follows — and the connection is not:
     * it is still open and still writable, so a handler can answer a peer
     * that half-closed with what the socket takes at once (in Pipeline mode
     * the close follows this call, and does not wait for a flush the socket
     * refused). Delivered once, as [InboundHandler.onReadClosed],
     * between the activation and the ending; held ahead of the first inbound
     * handler and replayed to one added later, and not delivered once the
     * ending was or the transport is gone. A chain that has handlers but no
     * inbound one never asks for a drain, so the event is swept there and
     * then instead — reaching nobody, and leaving the channel to decide on
     * it. This is the report, whoever makes it: the
     * channel makes it from the transport's own, and in Pipeline mode the
     * delivery closes the channel, since nobody else owns the connection
     * there — a handler calling this from inside the chain (a TLS
     * close_notify) gets that close too. A handler that only wants the ones
     * below it told passes the event on with
     * [PipelineHandlerContext.propagateReadClosed], which starts no sweep,
     * marks nothing delivered and closes nothing.
     */
    fun notifyReadClosed(): Pipeline = this

    /**
     * Notifies the pipeline that the connection has ended.
     *
     * The end, not the peer's end of file — that is [notifyReadClosed]. No
     * inbound event follows, nothing can be written. Delivered once, as
     * [InboundHandler.onInactive]; a close of the channel delivers it too.
     */
    fun notifyInactive(): Pipeline

    /** Notifies the pipeline that an error has occurred. */
    fun notifyError(cause: Throwable): Pipeline

    /** Fires a user-defined event through the pipeline (inbound, HEAD → TAIL). */
    fun notifyUserEvent(event: Any): Pipeline

    /** Notifies the pipeline that the channel's writability has changed. */
    fun notifyWritabilityChanged(isWritable: Boolean): Pipeline

    // --- Outbound: external code requests operations ---

    /** Requests a write through the pipeline. */
    fun requestWrite(msg: Any): Pipeline

    /** Requests a flush through the pipeline. */
    fun requestFlush(): Pipeline

    /**
     * Requests a close through the pipeline: the outbound walk from the tail
     * in which each handler hears [OutboundHandler.onClose] at most once, the
     * head closing the transport when the walk reaches it.
     *
     * A walk ends where a handler does not pass the close on. Handlers that
     * already heard their close are passed over by a later walk, so a request
     * after a walk finds nothing left to ask and does nothing; a request made
     * while a walk is running is served after it, as a new walk from the
     * tail. The completion of a walk is the end of the pipeline's life: the
     * transport is closed if it still is not, the ending is delivered if it
     * was not, and every handler is removed — the release of what a handler
     * owns is `handlerRemoved`'s, not the walk's.
     *
     * Runs on the owning context: handed to it from another thread, and,
     * when that context has stopped and can no longer take anything, run on
     * the caller's thread instead, under the quiescence a stopped loop
     * implies — the same premise the transport's own close from that thread
     * rests on. A second closer on a stopped loop finds the pipeline claimed
     * and does nothing.
     */
    fun requestClose(): Pipeline

    /** Convenience: requestWrite + requestFlush. */
    fun requestWriteAndFlush(msg: Any): Pipeline {
        requestWrite(msg)
        requestFlush()
        return this
    }
}
