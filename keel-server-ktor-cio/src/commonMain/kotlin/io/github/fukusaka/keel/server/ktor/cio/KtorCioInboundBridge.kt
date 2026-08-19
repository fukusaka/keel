package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlin.reflect.KClass

/**
 * Pipeline handler that bridges inbound [IoBuf]s directly to a coroutine
 * [Channel][kotlinx.coroutines.channels.Channel], ownership transferred to
 * the receiver.
 *
 * Mirrors the [io.github.fukusaka.keel.pipeline.SuspendMessageBridge] shape
 * used by `:keel-codec-http` and `:keel-server-ktor`, replacing the indirect
 * `BufferedSuspendSource(SuspendBridgeHandler)` chain previously used by
 * [KtorCioConnectionHandler].  The chain shortened the close propagation
 * path from 4 hops (notifyInactive → SuspendBridgeHandler →
 * BufferedSuspendSource → ByteChannel) to 2 hops (notifyInactive → bridge
 * Channel close), eliminating cross-context dispatch latency that surfaced
 * as accept-burst close-propagation starvation under single-thread-per-
 * engine dispatchers.
 *
 * **Ownership**: every [IoBuf] delivered via [receiveCatching] is owned by
 * the receiver and MUST be released with [IoBuf.release].  Buffers that
 * cannot be queued (channel closed) are released here so the engine sees
 * no leaked buffers.
 *
 * **Backpressure**: the in-flight queue's cumulative `readableBytes` are
 * tracked; crossing [INBOUND_HIGH_WATERMARK_BYTES] flips the underlying
 * transport's read side off via [io.github.fukusaka.keel.pipeline.PipelinedChannel.pauseReads],
 * draining back below [INBOUND_LOW_WATERMARK_BYTES] re-arms it via
 * [io.github.fukusaka.keel.pipeline.PipelinedChannel.resumeReads]. Without
 * this bound a slow Ktor handler would let the bridge channel grow without
 * limit and defeat TCP flow control to the peer. Hysteresis (64 KiB / 32
 * KiB, the same values as
 * [io.github.fukusaka.keel.pipeline.SuspendBridgeHandler]) avoids flapping
 * on every delivery.
 *
 * **Thread safety**: callbacks ([onRead] / [onInactive] / [onError]) run
 * on the EventLoop thread, and the pump that calls [receiveCatching] /
 * [close] also runs on the channel's `ioDispatcher` (the same EventLoop).
 * The mutable backpressure state ([pendingBytes],
 * [readsPausedByBackpressure]) is therefore single-threaded; the internal
 * coroutine [Channel][kotlinx.coroutines.channels.Channel] handles the
 * `trySend` ↔ `receiveCatching` handoff.
 */
internal class KtorCioInboundBridge : InboundHandler {

    override val acceptedType: KClass<*> get() = IoBuf::class

    private val inbound = Channel<IoBuf>(Channel.UNLIMITED)

    private var ctx: PipelineHandlerContext? = null

    /** Cumulative `readableBytes` of buffers currently queued in [inbound]. */
    private var pendingBytes: Int = 0

    /**
     * `true` when this bridge asked the transport to stop draining reads
     * because [pendingBytes] reached the high water mark. Cleared once the
     * pump dequeues back below the low water mark. Guards against
     * double-pause / double-resume (the transport's pause/resume are not
     * idempotent) and is reset (without a resume call) by [close] /
     * [onInactive] / [onError] when the connection is tearing down.
     */
    private var readsPausedByBackpressure: Boolean = false

    override fun handlerAdded(ctx: PipelineHandlerContext) {
        this.ctx = ctx
    }

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is IoBuf) {
            val bytes = msg.readableBytes
            val result = inbound.trySend(msg)
            if (result.isFailure) {
                // Channel closed — release ownership we just received
                // from the pipeline.
                msg.release()
                return
            }
            pendingBytes += bytes
            if (!readsPausedByBackpressure && pendingBytes >= INBOUND_HIGH_WATERMARK_BYTES) {
                readsPausedByBackpressure = true
                ctx.channel.pauseReads()
            }
            // IoBufs are terminal here; do not propagate to TAIL.
        } else {
            ctx.propagateRead(msg)
        }
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        // Transport is being torn down; the close() path (or the pump's
        // own teardown) will release any remaining queued buffers and we
        // must not call resumeReads on a dead transport.
        readsPausedByBackpressure = false
        inbound.close()
        ctx.propagateInactive()
    }

    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
        readsPausedByBackpressure = false
        // Handled here, and not passed on: everything this bridge feeds is
        // finished and the reason went with it, so there is nothing left for
        // a later handler to do about it. This is the last handler in the
        // pipelines that install it, and passing it on would reach the tail,
        // which records what arrives there as an application bug -- on the
        // ordinary path where a peer disappears mid-write.
        inbound.close(cause)
    }

    /**
     * Suspends until the next inbound [IoBuf] arrives or the bridge is
     * closed (peer EOF, error, or [close]).
     *
     * On a successful result, the caller owns the returned [IoBuf] and
     * MUST release it. Dequeueing decrements the backpressure accounting
     * and, if the queue drains back below
     * [INBOUND_LOW_WATERMARK_BYTES], re-arms the transport's read side.
     */
    suspend fun receiveCatching(): ChannelResult<IoBuf> {
        val result = inbound.receiveCatching()
        val buf = result.getOrNull()
        if (buf != null) {
            pendingBytes -= buf.readableBytes
            if (readsPausedByBackpressure && pendingBytes <= INBOUND_LOW_WATERMARK_BYTES) {
                readsPausedByBackpressure = false
                ctx?.channel?.resumeReads()
            }
        }
        return result
    }

    /**
     * Drains and releases any queued buffers, then closes the bridge.
     *
     * Idempotent.  Used by [KtorCioConnectionHandler] in its `finally`
     * block to guarantee buffers are released when the keep-alive loop
     * exits before the pipeline sees [onInactive] (e.g. on cancellation).
     * The watermark state is reset silently — we are on the teardown path
     * and calling `resumeReads` on a closing transport is incorrect.
     */
    fun close() {
        while (true) {
            val r = inbound.tryReceive()
            val buf = r.getOrNull() ?: break
            buf.release()
        }
        pendingBytes = 0
        readsPausedByBackpressure = false
        inbound.close()
    }
}

/**
 * Inbound bridge backpressure watermarks. Crossing high suspends the
 * transport's read side via `pauseReads`, dropping back below low re-arms
 * it via `resumeReads`. Hysteresis avoids flapping on every delivery.
 * Values mirror the `SuspendBridgeHandler` read-side watermarks (64 KiB /
 * 32 KiB) so a slow handler on the cio-keel path is bounded the same way
 * as on the keel-codec path.
 */
private const val INBOUND_HIGH_WATERMARK_BYTES = 64 * 1024
private const val INBOUND_LOW_WATERMARK_BYTES = 32 * 1024
