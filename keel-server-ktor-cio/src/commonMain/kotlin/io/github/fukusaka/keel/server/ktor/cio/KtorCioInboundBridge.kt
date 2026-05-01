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
 * Pattern C structural alignment with Pattern B
 * ([io.github.fukusaka.keel.pipeline.SuspendMessageBridge]): replaces the
 * indirect `BufferedSuspendSource(SuspendBridgeHandler)` chain previously
 * used by [KtorCioConnectionHandler].  The chain shortened the close
 * propagation path from 4 hops (notifyInactive → SuspendBridgeHandler →
 * BufferedSuspendSource → ByteChannel) to 2 hops (notifyInactive → bridge
 * Channel close), eliminating cross-context dispatch latency that surfaced
 * as Pattern C accept-burst close-propagation starvation under
 * single-thread-per-engine dispatchers.
 *
 * **Ownership**: every [IoBuf] delivered via [receiveCatching] is owned by
 * the receiver and MUST be released with [IoBuf.release].  Buffers that
 * cannot be queued (channel closed, capacity exceeded with bounded
 * capacity) are released here so the engine sees no leaked buffers.
 *
 * **Capacity**: [Channel.UNLIMITED] is the default.  Producer (EventLoop)
 * and consumer (pump coroutine) typically run on different threads;
 * `trySend` then never suspends and never drops successfully-delivered
 * data.  The pump drains promptly to a Ktor `ByteChannel`, so the in-flight
 * queue stays small in practice.
 *
 * **Thread safety**: callbacks run on the EventLoop thread;
 * [receiveCatching] suspends from any thread.  The internal coroutine
 * [Channel][kotlinx.coroutines.channels.Channel] handles the cross-thread
 * handoff.
 */
internal class KtorCioInboundBridge : InboundHandler {

    override val acceptedType: KClass<*> get() = IoBuf::class

    private val inbound = Channel<IoBuf>(Channel.UNLIMITED)

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is IoBuf) {
            val result = inbound.trySend(msg)
            if (result.isFailure) {
                // Channel closed (or full, for bounded capacity) — release
                // ownership we just received from the pipeline.
                msg.release()
            }
            // IoBufs are terminal here; do not propagate to TAIL.
        } else {
            ctx.propagateRead(msg)
        }
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        inbound.close()
        ctx.propagateInactive()
    }

    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
        inbound.close(cause)
        ctx.propagateError(cause)
    }

    /**
     * Suspends until the next inbound [IoBuf] arrives or the bridge is
     * closed (peer EOF, error, or [close]).
     *
     * On a successful result, the caller owns the returned [IoBuf] and
     * MUST release it.
     */
    suspend fun receiveCatching(): ChannelResult<IoBuf> = inbound.receiveCatching()

    /**
     * Drains and releases any queued buffers, then closes the bridge.
     *
     * Idempotent.  Used by [KtorCioConnectionHandler] in its `finally`
     * block to guarantee buffers are released when the keep-alive loop
     * exits before the pipeline sees [onInactive] (e.g. on cancellation).
     */
    fun close() {
        while (true) {
            val r = inbound.tryReceive()
            val buf = r.getOrNull() ?: break
            buf.release()
        }
        inbound.close()
    }
}
