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
 * All pipeline operations must be called on the EventLoop thread.
 */
interface Pipeline {

    /** The channel this pipeline belongs to. */
    val channel: PipelinedChannel

    // --- Pipeline composition ---

    /** Adds a handler at the beginning of the pipeline (after HEAD). */
    fun addFirst(name: String, handler: PipelineHandler): Pipeline

    /** Adds a handler at the end of the pipeline (before TAIL). */
    fun addLast(name: String, handler: PipelineHandler): Pipeline

    /** Adds a handler before the handler named [baseName]. */
    fun addBefore(baseName: String, name: String, handler: PipelineHandler): Pipeline

    /** Adds a handler after the handler named [baseName]. */
    fun addAfter(baseName: String, name: String, handler: PipelineHandler): Pipeline

    /** Removes the handler with the given [name] and returns it. */
    fun remove(name: String): PipelineHandler

    /** Replaces the handler named [oldName] with [newHandler] and returns the old handler. */
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

    /** Notifies the pipeline that the channel is now inactive. */
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

    /** Requests a close through the pipeline. */
    fun requestClose(): Pipeline

    /** Convenience: requestWrite + requestFlush. */
    fun requestWriteAndFlush(msg: Any): Pipeline {
        requestWrite(msg)
        requestFlush()
        return this
    }
}
