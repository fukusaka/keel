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
 * - [write]: delegates to [PipelineHandlerContext.propagateWrite]
 * - [flush]: delegates to [PipelineHandlerContext.propagateFlush]
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
 *
 * **Bounded queue (read backpressure)**: the queue's readable bytes are
 * accounted on every enqueue/dequeue. Crossing [HIGH_WATERMARK_BYTES] flips
 * the channel's `readEnabled` off, so the engine stops draining the socket
 * and TCP flow control reaches the peer; draining back to
 * [LOW_WATERMARK_BYTES] re-arms it. Without the bound, a consumer slower
 * than its peer accumulated the peer's entire send stream in this queue
 * (the engine kept reading, so the kernel's receive window never closed).
 * The flip is per-connection, via [PipelinedChannel.pauseReads] /
 * [PipelinedChannel.resumeReads] — the flow-control knob every engine
 * implements as "stop consuming within a bounded overshoot, no data
 * loss" regardless of its `IdleReadPolicy`. Remaining engine caveat:
 * io_uring multishot recv's in-flight SQE keeps delivering until it is
 * cancelled (its pause currently falls back to the no-re-arm semantics),
 * so on that tier the bound stays soft pending the multishot-cancel
 * work. While the bridge has suspended reads it owns the channel's
 * pause state; a consumer that manages `readEnabled` / pause manually
 * should not also read through this bridge.
 */
class SuspendBridgeHandler : DuplexHandler, OwnedSuspendSource {

    private val readQueue = ArrayDeque<IoBuf>()
    private var readCont: CancellableContinuation<Unit>? = null
    private var eof = false
    private lateinit var ctx: PipelineHandlerContext

    // Readable bytes currently sitting in [readQueue]. EventLoop-thread only,
    // like the queue itself.
    private var queuedBytes = 0L

    /**
     * True while this bridge has flipped the channel's `readEnabled` off
     * because [queuedBytes] crossed [HIGH_WATERMARK_BYTES]. Internal so
     * [PipelinedChannel.read]'s lazy first-read arming does not fight the
     * watermark (re-arming is the dequeue path's job, at
     * [LOW_WATERMARK_BYTES]).
     */
    internal var readSuspendedByWatermark = false
        private set

    // ---- Watermark observability (I/O-thread only, plain counters) ----
    // Grounding data for the 64 KiB / 32 KiB initial values: how often a
    // real workload hits the watermark (flapping shows up as a high pause
    // count) and how deep the backlog actually gets. Read after the
    // connection quiesces (tests / future diagnostics).

    /** Times the high watermark suspended this connection's read. */
    internal var pauseCount = 0L
        private set

    /** Times the low watermark re-armed it. */
    internal var resumeCount = 0L
        private set

    /** Highest backlog ever observed, in bytes. */
    internal var maxQueuedBytes = 0L
        private set

    /**
     * Whether the bridge has observed pipeline inactivation. Exposed for
     * unit tests of [AbstractPipelinedChannel]'s deferred-close path; user
     * code should observe EOF via [read] returning `-1` instead of polling
     * this flag.
     */
    internal val isEof: Boolean get() = eof

    override fun handlerAdded(ctx: PipelineHandlerContext) {
        this.ctx = ctx
    }

    // --- Inbound: push → pull bridge ---

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is IoBuf) {
            readQueue.addLast(msg)
            queuedBytes += msg.readableBytes
            if (queuedBytes > maxQueuedBytes) maxQueuedBytes = queuedBytes
            // High watermark: stop the engine's socket drain so TCP flow
            // control reaches the peer instead of this queue growing
            // unboundedly. Re-armed by the dequeue path at the low
            // watermark (hysteresis avoids flapping on every delivery).
            if (!readSuspendedByWatermark && queuedBytes >= HIGH_WATERMARK_BYTES) {
                readSuspendedByWatermark = true
                pauseCount++
                ctx.channel.pauseReads()
            }
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

    /**
     * Records [n] bytes leaving the queue and re-arms the channel's read
     * once the backlog has drained to [LOW_WATERMARK_BYTES] (only when this
     * bridge was the one that suspended it).
     */
    private fun onDequeued(n: Int) {
        queuedBytes -= n
        if (readSuspendedByWatermark && queuedBytes <= LOW_WATERMARK_BYTES) {
            readSuspendedByWatermark = false
            resumeCount++
            ctx.channel.resumeReads()
        }
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        eof = true
        // Release all queued buffers that will never be consumed. The
        // watermark state resets without re-arming: the channel is going
        // away and arming a dead transport's read would be a no-op at best.
        for (buf in readQueue) {
            buf.release()
        }
        readQueue.clear()
        queuedBytes = 0
        readSuspendedByWatermark = false
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
        onDequeued(n)
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
        val received = readQueue.removeFirst()
        onDequeued(received.readableBytes)
        return received
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
     * Non-suspend: propagateFlush triggers IoTransport.flush() and does not
     * consult the result. A completion does travel the pipeline as
     * `onFlushComplete`, but this handler does not override it and so observes
     * none — it inherits the default, which passes it on, and it sits last, so
     * the event reaches the tail. A caller that needs to know its bytes have
     * gone waits on the channel.
     */
    fun flush() {
        ctx.propagateFlush()
    }

    companion object {
        /**
         * Queue backlog at which the channel's read is suspended, in bytes.
         * Initial value pending workload measurements — 64 KiB / 32 KiB
         * follow the conventional write-watermark pairing; the right read
         * bound is workload-dependent and should be revisited with real
         * profiles.
         */
        internal const val HIGH_WATERMARK_BYTES = 64L * 1024

        /** Backlog at which a suspended read is re-armed; see [HIGH_WATERMARK_BYTES]. */
        internal const val LOW_WATERMARK_BYTES = 32L * 1024
    }
}
