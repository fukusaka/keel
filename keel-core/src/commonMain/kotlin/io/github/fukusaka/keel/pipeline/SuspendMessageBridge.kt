package io.github.fukusaka.keel.pipeline

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlin.reflect.KClass

/**
 * Pipeline handler that bridges typed inbound messages to a suspendable
 * [Channel][kotlinx.coroutines.channels.Channel], enabling coroutine-based
 * consumers to receive pipeline-decoded messages.
 *
 * Messages matching [type] are sent to the internal channel via [trySend]
 * (non-blocking). Non-matching messages are propagated downstream unchanged.
 * Connection close ([onInactive]) and errors ([onError]) close the channel
 * so that the suspend receiver terminates cleanly.
 *
 * **Usage with pipeline HTTP codec**:
 * ```
 * // Pipeline: encoder ↔ decoder ↔ aggregator ↔ bridge ↔ TAIL
 * val bridge = SuspendMessageBridge(HttpRequest::class)
 * pipeline.addLast("bridge", bridge)
 *
 * // Suspend loop on a coroutine:
 * while (true) {
 *     val result = bridge.receiveCatching()
 *     if (result.isClosed) break
 *     val request = result.getOrThrow()
 *     // handle request...
 * }
 * ```
 *
 * **Capacity**: [Channel.UNLIMITED] is recommended for HTTP where the
 * producer (EventLoop) and consumer (application coroutine) run on
 * different threads. [trySend] never suspends and always succeeds with
 * unlimited capacity, avoiding message loss.
 *
 * @param type the [KClass] of messages to intercept and queue.
 * @param capacity the coroutine channel buffer capacity.
 */
class SuspendMessageBridge<T : Any>(
    private val type: KClass<T>,
    capacity: Int = Channel.UNLIMITED,
) : InboundHandler {

    override val acceptedType: KClass<*> get() = type

    private val messages = Channel<T>(capacity)

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (type.isInstance(msg)) {
            @Suppress("UNCHECKED_CAST")
            val result = messages.trySend(msg as T)
            if (result.isFailure) {
                // Channel full or closed — propagate downstream as fallback.
                ctx.propagateRead(msg)
            }
        } else {
            ctx.propagateRead(msg)
        }
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        messages.close()
        ctx.propagateInactive()
    }

    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
        messages.close(cause)
        ctx.propagateError(cause)
    }

    /**
     * Suspends until the next typed message is available.
     *
     * Returns a [ChannelResult] that is:
     * - successful with the message value on normal delivery,
     * - closed with `null` exception on clean EOF ([onInactive]),
     * - closed with the cause on error ([onError]).
     */
    suspend fun receiveCatching(): ChannelResult<T> = messages.receiveCatching()
}
