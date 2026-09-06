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
 * The peer's end of file ([InboundHandler.onReadClosed]) is not one of them:
 * it is passed on, and this bridge's receiver learns of it only through the
 * ending that follows. A chain ending in this bridge is one keel owns, so
 * that ending does follow — the channel closes itself on the peer's end of
 * file — but the consequence is that a consumer here cannot answer a peer
 * that half-closed. Giving it that is for the first engine that reports the
 * peer's end of file apart from the connection's end; until one does, no
 * transport in this tree reports it at all.
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
 * **Pooled-payload messages ([releaseUndelivered])**: a message type may
 * own pooled buffers that must be released if the message is never
 * consumed (e.g. a `WsFrame` carrying a pooled inbound payload). Pass
 * [releaseUndelivered] so the bridge releases those buffers for messages
 * that are buffered-but-undelivered when the channel closes, or that
 * arrive after the channel has closed (the [trySend] failure path). The
 * sole consumer drains the channel via [receiveCatching], so any message
 * still buffered at close was genuinely never delivered; the release runs
 * exactly once per message because the channel hands each element to at
 * most one of {consumer, this drain, the trySend-failure path}. Defaults
 * to null (the message type owns no pooled resources — HTTP), so existing
 * bridges are unchanged.
 *
 * @param type the [KClass] of messages to intercept and queue.
 * @param capacity the coroutine channel buffer capacity.
 * @param releaseUndelivered optional release hook for undelivered pooled
 *   messages (see above); null when the message type owns no pooled
 *   resources.
 */
class SuspendMessageBridge<T : Any>(
    private val type: KClass<T>,
    capacity: Int = Channel.UNLIMITED,
    private val releaseUndelivered: ((T) -> Unit)? = null,
) : InboundHandler {

    override val acceptedType: KClass<*> get() = type

    // onUndeliveredElement closes the prompt-cancellation window: when a message
    // is handed to a receiver that is cancelled before receiveCatching() returns
    // it, the channel reports it here so its pooled payload is released rather
    // than lost. This does not double-release with the other paths: a graceful
    // close() keeps buffered elements receivable (drained by releaseBuffered),
    // and a failed trySend never accepts the element — so onUndeliveredElement
    // fires only for the receiver-cancellation case those two do not cover.
    // Thread affinity: the hook runs on the receiving coroutine's own thread (it
    // fires while that receive unwinds), which for keel consumers is the channel
    // EventLoop thread — they confine receiveCatching to ioDispatcher — so a
    // pooled release here stays on the buffer-owning thread even when the cancel
    // originates elsewhere (verified: a cancel from another thread still fires
    // the hook on the confined receiver's thread).
    private val messages = Channel<T>(capacity, onUndeliveredElement = { releaseUndelivered?.invoke(it) })

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (type.isInstance(msg)) {
            @Suppress("UNCHECKED_CAST")
            val typed = msg as T
            val result = messages.trySend(typed)
            if (result.isFailure) {
                // Channel full or closed. For a pooled-payload message type,
                // release its buffers here — propagating to a downstream that
                // does not own the type (TAIL) would silently leak them.
                // Otherwise propagate downstream as the original fallback.
                val release = releaseUndelivered
                if (release != null) release(typed) else ctx.propagateRead(msg)
            }
        } else {
            ctx.propagateRead(msg)
        }
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        messages.close()
        releaseBuffered()
        ctx.propagateInactive()
    }

    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
        messages.close(cause)
        releaseBuffered()
        ctx.propagateError(cause)
    }

    /**
     * Removal is this bridge's ending too: nothing reaches a removed handler,
     * so the receiver is told the stream is over. What is already buffered
     * stays receivable — the consumer drains it, or its own teardown
     * ([closeAndReleaseBuffered]) releases it. At the end of a channel's life
     * the ending ([onInactive]) has already released the buffer before the
     * removal; a bridge removed from a still-live connection keeps its
     * buffered messages for the consumer, so a consumer that has already left
     * must have run its teardown, or pooled payloads stay buffered.
     */
    override fun handlerRemoved(ctx: PipelineHandlerContext) {
        messages.close()
    }

    /**
     * Closes the channel and releases any buffered-but-undelivered messages.
     *
     * A server-initiated teardown now does deliver the ending — a channel's
     * `close()` tells its pipeline — so [onInactive] releases the buffer on
     * that path too. This remains the consumer's own hook, called from its
     * teardown, because the consumer can stop before anything closes the
     * channel, and a message buffered for a consumer that left is stranded
     * either way. Closing the channel
     * here also makes any *later* [trySend] (a decoder frame the EventLoop
     * delivers after this consumer stopped) fail and take the
     * [releaseUndelivered] path in [onRead] instead of leaking — so this plus
     * the [onRead] failure branch close the whole race window without
     * coordinating with the EventLoop.
     *
     * Idempotent and safe alongside the close hooks: [Channel.close] is a
     * no-op when already closed, and each buffered message is handed to
     * exactly one drainer via the atomic [Channel.tryReceive]. No-op for the
     * pooled-payload bookkeeping unless [releaseUndelivered] is set.
     */
    fun closeAndReleaseBuffered() {
        messages.close()
        releaseBuffered()
    }

    /**
     * Drains and releases any buffered-but-undelivered messages after the
     * channel is closed. No-op unless [releaseUndelivered] is set — without
     * a pooled-payload type there is nothing to free, and the consumer
     * receives the remaining buffered messages normally after close.
     */
    private fun releaseBuffered() {
        val release = releaseUndelivered ?: return
        while (true) {
            val result = messages.tryReceive()
            val msg = result.getOrNull() ?: break
            release(msg)
        }
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

/**
 * Builds a [SuspendMessageBridge] for messages of type [T] without naming the
 * [KClass] explicitly — the reified counterpart of the [SuspendMessageBridge]
 * constructor, for wiring a bridge into a hand-built pipeline (e.g. a custom
 * HTTP client on `addHttp1ClientCodec`).
 *
 * @param capacity the coroutine channel buffer capacity (default unlimited).
 * @param releaseUndelivered optional release hook for undelivered pooled
 *   messages; pass `null` (default) when [T] owns no pooled resources, or e.g.
 *   `{ it.headers.release() }` for a pooled response whose headers must be
 *   released if the connection is torn down while a message is still buffered.
 */
public inline fun <reified T : Any> suspendMessageBridge(
    capacity: Int = Channel.UNLIMITED,
    noinline releaseUndelivered: ((T) -> Unit)? = null,
): SuspendMessageBridge<T> = SuspendMessageBridge(T::class, capacity, releaseUndelivered)
