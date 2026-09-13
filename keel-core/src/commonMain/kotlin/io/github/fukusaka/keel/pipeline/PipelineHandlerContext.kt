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
 * **Threading**: handler callbacks run on the EventLoop thread, so a handler
 * propagating from inside one is already there. The outbound propagations
 * ([propagateWrite], [propagateFlush], [propagateClose]) are also safe from
 * another thread — a handler that finished asynchronous work off the loop can
 * emit from there, and the call dispatches onto the EventLoop rather than
 * touching the transport's state from the caller's thread. The work then
 * happens after the call returns. Inbound propagation must stay on the
 * EventLoop thread.
 *
 * ### Ownership on throw ([propagateRead] / [propagateWrite])
 *
 * When a message carries a pooled, ref-counted buffer (an `IoBuf` or an
 * `IoBufChunks` wrapped in a frame / body), [propagateRead] and
 * [propagateWrite] transfer ownership of that buffer **down the chain
 * only when the call returns normally**. A synchronous throw from a
 * downstream handler means the message was *not* accepted, so ownership
 * stays with the caller — the caller must release the buffer on its
 * catch path, then rethrow.
 *
 * ```kotlin
 * val out = allocator.allocate(n)
 * fillIt(out)
 * try {
 *     ctx.propagateRead(HttpBody(out))   // ownership transfers iff this returns
 * } catch (t: Throwable) {
 *     out.release()                       // downstream rejected it — still ours
 *     throw t
 * }
 * ```
 *
 * Forgetting this leaks one pooled buffer per aborted message on every
 * keep-alive cycle (the root cause of the codec-http `emitDecodedChunk`
 * / `CompressionHandler.emitWorking` leaks). A handler that allocated a
 * buffer it has not yet handed off must release it on any error path,
 * whether the error came from the buffer fill, a limit check, or the
 * `propagate*` call itself.
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

    /**
     * The channel's buffer allocator. Cheapest on the channel's EventLoop —
     * with the default pooled allocator a lock-free freelist fast path; an
     * off-EventLoop release is safe but takes the slower cross-context path.
     */
    val allocator: BufferAllocator

    // --- Inbound propagation: next inbound handler ---

    /** Propagates a channel-active event to the next inbound handler. */
    fun propagateActive()

    /**
     * Propagates a read event to the next inbound handler.
     *
     * If [msg] carries a pooled buffer, ownership transfers downstream
     * only on normal return — see the "Ownership on throw" section in the
     * [PipelineHandlerContext] KDoc. A throw means the caller still owns
     * the buffer and must release it before rethrowing.
     */
    fun propagateRead(msg: Any)

    /** Propagates a read-complete event to the next inbound handler. */
    fun propagateReadComplete()

    /** Passes a flush completion to the next inbound handler. */
    fun propagateFlushComplete()

    /**
     * Passes the peer's end of file to the next inbound handler — and says,
     * by passing it, that this handler is not the one that answers for the
     * connection.
     *
     * Not calling it is the other answer: the handler keeps the event, which
     * claims the connection. A claimant owes it one of the two endings —
     * close it, or pass the event on after all — since nothing below is
     * offered what was taken and the tail answers for nothing.
     *
     * This is also how a handler raises the end of file itself, for a
     * protocol whose own close means the peer has finished: a codec turning
     * a TLS `close_notify` into this event tells the chain below it the read
     * side is over, and the chain answers as it would for a transport's
     * report. It is the only way in from inside the chain.
     *
     * What it reaches is the chain below this context as it stands now. A
     * handler that joins afterwards, and a context that activates
     * afterwards, are not offered it — unlike the transport's own report,
     * which every context is owed. See [InboundHandler.onReadClosed].
     */
    fun propagateReadClosed() {
    }

    /** Propagates a channel-inactive event to the next inbound handler. */
    fun propagateInactive()

    /** Propagates an error to the next inbound handler. */
    fun propagateError(cause: Throwable)

    /** Propagates a user event to the next inbound handler. */
    fun propagateUserEvent(event: Any)

    /** Propagates a writability change to the next inbound handler. */
    fun propagateWritabilityChanged(isWritable: Boolean)

    // --- Outbound propagation: next outbound handler ---

    /**
     * Propagates a write request to the next outbound handler.
     *
     * If [msg] carries a pooled buffer, ownership transfers downstream
     * only on normal return — see the "Ownership on throw" section in the
     * [PipelineHandlerContext] KDoc. A throw means the caller still owns
     * the buffer and must release it before rethrowing.
     */
    fun propagateWrite(msg: Any)

    /** Propagates a flush request to the next outbound handler. */
    fun propagateFlush()

    /**
     * Propagates a close request to the next outbound handler that has not
     * heard it.
     *
     * From inside the handler's own `onClose` this is the next hop of the
     * running walk. From anywhere else it starts a close from this context
     * (the Netty `ctx.close()` idiom): a walk from here toward the head, whose
     * completion releases the transport and ends the pipeline's life — the
     * handlers on the tail side then hear the ending and `handlerRemoved`,
     * not their close, unless a close of the channel is asked for first.
     */
    fun propagateClose()

    /** Convenience: propagateWrite + propagateFlush. */
    fun propagateWriteAndFlush(msg: Any) {
        propagateWrite(msg)
        propagateFlush()
    }
}
