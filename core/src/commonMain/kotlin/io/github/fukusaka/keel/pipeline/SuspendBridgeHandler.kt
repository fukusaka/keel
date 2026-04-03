package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
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
 * - [read]: suspends until data is available, then dequeues and copies
 * - [onInactive]: signals EOF so [read] returns -1
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
 * **Thread safety**: all methods are called on the EventLoop thread.
 * The suspend continuation is resumed on the same thread via EventLoop dispatch.
 */
internal class SuspendBridgeHandler : ChannelDuplexHandler {

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
            readCont?.let { cont ->
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
        readCont?.let { cont ->
            readCont = null
            cont.resume(Unit)
        }
        ctx.propagateInactive()
    }

    // --- App-facing suspend API ---

    /**
     * Suspends until inbound data is available, then copies into [buf].
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
        // Copy bytes from received into buf.
        for (i in 0 until n) {
            buf.writeByte(received.readByte())
        }
        if (received.readableBytes > 0) {
            // Partial consumption — put back for next read.
            readQueue.addFirst(received)
        } else {
            received.release()
        }
        return n
    }

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
