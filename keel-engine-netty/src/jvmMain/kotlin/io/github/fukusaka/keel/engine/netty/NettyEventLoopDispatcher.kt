package io.github.fukusaka.keel.engine.netty

import io.netty.channel.EventLoop
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * [CoroutineDispatcher] that runs every dispatched task on a Netty
 * [EventLoop] — the per-channel serial executor that Netty already
 * uses for inbound callbacks ([io.netty.channel.ChannelInboundHandlerAdapter.channelRead],
 * [io.netty.channel.ChannelInboundHandlerAdapter.channelInactive]) and
 * for [io.netty.channel.ChannelFuture] listeners.
 *
 * **Why this exists**:
 * [io.github.fukusaka.keel.pipeline.SuspendBridgeHandler] (in `keel-core`)
 * has a documented single-thread invariant — all methods (`onRead`,
 * `onInactive`, `read`, `write`, `flush`) must run on the same thread.
 * Other EventLoop-based engines (epoll / kqueue / io_uring / nio)
 * satisfy this trivially because their `ioDispatcher` *is* the
 * single-thread EventLoop.
 *
 * Previously [NettyIoTransport.ioDispatcher] pointed at
 * [kotlinx.coroutines.Dispatchers.Default], so coroutine-side
 * `withContext(ioDispatcher)` hops (e.g. `PipelinedChannel.read`) ran on a
 * Default worker thread while Netty inbound callbacks fired on the
 * channel's EventLoop. Two threads touched
 * `SuspendBridgeHandler.readCont` / `readQueue` / `eof` without any
 * memory-visibility guarantees. On the JVM, the memory model plus
 * Netty's internal barriers happen to keep the race latent, but the
 * structure is identical to the one that deterministically failed on
 * Kotlin/Native in `NwEngineTest` (see PR #309 and
 * [io.github.fukusaka.keel.engine.nwconnection.NwConnectionQueueDispatcher]).
 *
 * By pointing `ioDispatcher` at the same EventLoop Netty inbound
 * callbacks already use, coroutine resumptions and native callbacks
 * run on a single serial thread, matching every other EventLoop
 * engine's invariant.
 *
 * **Dispatch elision**: [isDispatchNeeded] returns `false` when the
 * caller is already running on [eventLoop] (detected via Netty's own
 * [EventLoop.inEventLoop]). The coroutine runtime then runs the block
 * inline, avoiding a task-queue round-trip on every
 * `withContext(ioDispatcher)` hop whose caller was already resumed on
 * the EventLoop thread by an inbound callback. The NWConnection queue
 * dispatcher elides the same way; the readiness (epoll / kqueue), io_uring
 * and nio loops do not override `isDispatchNeeded` and always queue.
 *
 * **Implementation**: [dispatch] forwards to
 * [EventLoop.execute][io.netty.util.concurrent.EventExecutor.execute]
 * — the same primitive Netty uses internally for cross-thread task
 * submission. Exceptions thrown from the dispatched block propagate
 * through the coroutine runtime the same way they do on other
 * [CoroutineDispatcher] implementations.
 *
 * **Lifetime**: the EventLoop is owned by Netty; this dispatcher holds
 * a reference to keep it usable for as long as the channel is open.
 * When the channel is closed, Netty drains outstanding tasks on the
 * EventLoop before releasing it.
 */
internal class NettyEventLoopDispatcher(
    private val eventLoop: EventLoop,
) : CoroutineDispatcher() {

    override fun isDispatchNeeded(context: CoroutineContext): Boolean =
        !eventLoop.inEventLoop()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        eventLoop.execute(block)
    }
}
