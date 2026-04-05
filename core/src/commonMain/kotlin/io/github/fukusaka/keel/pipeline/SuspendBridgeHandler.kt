package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.io.OwnedSuspendSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pipeline handler that bridges push-based Pipeline I/O to pull-based
 * suspend [read]/[write]/[flush] operations.
 *
 * Installed as the last user handler (before TAIL) when a [PipelinedChannel]
 * is used as a suspend-based [Channel][io.github.fukusaka.keel.core.Channel].
 * Not needed in pure Pipeline mode (e.g. bindPipeline with RoutingHandler).
 *
 * **Inbound (push → pull)**:
 * - [onRead]: buffers incoming [IoBuf] in an internal queue
 * - [read]: suspends until data is available, then dequeues and bulk-copies
 * - [onInactive]: signals EOF, drains and releases queued buffers, so [read] returns -1
 *
 * **Outbound (direct propagation)**:
 * - [write]: delegates to [ChannelHandlerContext.propagateWrite]
 * - [flush]: delegates to [ChannelHandlerContext.propagateFlush]
 *
 * ```
 * Pipeline:  HEAD ↔ [handlers] ↔ SuspendBridgeHandler ↔ TAIL
 *
 * Inbound:   engine → notifyRead(buf) → handlers → onRead() → queue
 *                                                                ↓
 * App:                                              suspend read(buf)
 *
 * Outbound:  App → write(buf) → propagateWrite → handlers → HEAD → IoTransport
 *            App → flush()    → propagateFlush → handlers → HEAD → IoTransport
 * ```
 *
 * **Thread safety**: all methods — [onRead], [onInactive], [read], [write], [flush] —
 * must be called on the same EventLoop thread. The handler is not thread-safe.
 * The suspend continuation is resumed on the EventLoop thread via dispatch.
 *
 * **Single reader**: only one coroutine may call [read] at a time. Concurrent
 * readers will overwrite the pending continuation, causing the earlier reader
 * to hang indefinitely. This matches the Channel contract (single-threaded I/O).
 */
class SuspendBridgeHandler : ChannelDuplexHandler, OwnedSuspendSource {

    private val readQueue = ArrayDeque<IoBuf>()
    private var readCont: CancellableContinuation<Unit>? = null
    private var eof = false
    private lateinit var ctx: ChannelHandlerContext

    override fun handlerAdded(ctx: ChannelHandlerContext) {
        this.ctx = ctx
    }

    // --- Inbound: push → pull bridge ---

    override fun onRead(ctx: ChannelHandlerContext, msg: Any) {
        if (msg is IoBuf) {
            readQueue.addLast(msg)
            // Resume the single waiting reader, if any.
            // Safe: onRead runs on EventLoop thread, same as read().
            val cont = readCont
            if (cont != null) {
                readCont = null
                cont.resume(Unit)
            }
            // Do NOT propagate to TAIL — we consume the data here.
        } else {
            // Non-IoBuf messages propagate normally.
            ctx.propagateRead(msg)
        }
    }

    override fun onInactive(ctx: ChannelHandlerContext) {
        eof = true
        // Release all queued buffers that will never be consumed.
        for (buf in readQueue) {
            buf.release()
        }
        readQueue.clear()
        // Resume the waiting reader so it returns -1 (EOF).
        val cont = readCont
        if (cont != null) {
            readCont = null
            cont.resume(Unit)
        }
        ctx.propagateInactive()
    }

    // --- App-facing suspend API ---

    /**
     * Suspends until inbound data is available, then bulk-copies into [buf].
     *
     * **Single reader only**: only one coroutine may be suspended in [read]
     * at a time. Concurrent calls overwrite the pending continuation.
     *
     * @return number of bytes read, or -1 on EOF (peer closed / notifyInactive).
     */
    suspend fun read(buf: IoBuf): Int {
        // Wait for data or EOF.
        while (readQueue.isEmpty() && !eof) {
            suspendCancellableCoroutine { cont ->
                readCont = cont
                cont.invokeOnCancellation { readCont = null }
            }
        }
        if (readQueue.isEmpty()) return -1 // EOF

        val received = readQueue.removeFirst()
        val n = minOf(received.readableBytes, buf.writableBytes)
        // Bulk copy from received into buf.
        received.copyTo(buf, n)
        if (received.readableBytes > 0) {
            // Partial consumption — put back for next read.
            readQueue.addFirst(received)
        } else {
            received.release()
        }
        return n
    }

    // --- OwnedSuspendSource: zero-copy read from queue ---

    /**
     * Returns the next handler-processed [IoBuf] from the queue without copying.
     *
     * The caller receives ownership of the returned buffer and MUST call
     * [IoBuf.release] when done reading.
     *
     * @return An [IoBuf] with readable data, or `null` on EOF.
     */
    override suspend fun readOwned(): IoBuf? {
        while (readQueue.isEmpty() && !eof) {
            suspendCancellableCoroutine { cont ->
                readCont = cont
                cont.invokeOnCancellation { readCont = null }
            }
        }
        if (readQueue.isEmpty()) return null // EOF
        return readQueue.removeFirst()
    }

    /** No-op: resources are released in [onInactive] and [handlerRemoved]. */
    override fun close() {}

    /**
     * Writes [buf] through the Pipeline outbound path.
     *
     * Non-suspend: propagateWrite is synchronous (buffers in IoTransport).
     */
    fun write(buf: IoBuf) {
        ctx.propagateWrite(buf)
    }

    /**
     * Flushes through the Pipeline outbound path.
     *
     * Non-suspend: propagateFlush triggers IoTransport.flush() which
     * is fire-and-forget (async completion via onFlushComplete callback).
     */
    fun flush() {
        ctx.propagateFlush()
    }
}
