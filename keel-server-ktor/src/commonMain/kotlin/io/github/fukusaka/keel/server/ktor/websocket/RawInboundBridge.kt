package io.github.fukusaka.keel.server.ktor.websocket

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlin.reflect.KClass

/**
 * Pipeline handler that queues raw inbound [IoBuf]s into a coroutine
 * [Channel][kotlinx.coroutines.channels.Channel] for consumption by a
 * protocol upgrade session (e.g. Ktor's WebSocket plugin).
 *
 * Installed after the HTTP codec stack is removed during a
 * [io.ktor.http.content.OutgoingContent.ProtocolUpgrade] (see
 * [io.github.fukusaka.keel.server.ktor.KeelApplicationResponse.respondUpgrade]).
 * Inbound raw bytes bypass the HTTP decoder and arrive here directly;
 * [receiveCatching] suspends until data is available.
 *
 * **Ownership**: every [IoBuf] delivered via [receiveCatching] is owned by
 * the receiver and MUST be released with [IoBuf.release]. Buffers that
 * cannot be queued (channel closed) are released here.
 *
 * **Thread safety**: pipeline callbacks run on the EventLoop thread;
 * [receiveCatching] may be called from any thread. The internal
 * [Channel][kotlinx.coroutines.channels.Channel] handles the handoff.
 */
internal class RawInboundBridge : InboundHandler {

    override val acceptedType: KClass<*> get() = IoBuf::class

    private val inbound = Channel<IoBuf>(Channel.UNLIMITED)

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is IoBuf) {
            val result = inbound.trySend(msg)
            if (result.isFailure) msg.release()
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

    /** Suspends until the next [IoBuf] arrives or the bridge is closed. */
    suspend fun receiveCatching(): ChannelResult<IoBuf> = inbound.receiveCatching()

    /**
     * Drains and releases queued buffers, then closes the channel.
     * Called during upgrade session cleanup to avoid IoBuf leaks.
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
