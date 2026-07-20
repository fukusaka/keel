package io.github.fukusaka.keel.engine.netty

import io.netty.channel.nio.NioEventLoopGroup
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pin the **I/O ownership invariant funnel routing** for Netty — the
 * equivalent of the kqueue / epoll / nio funnel tests and
 * `NwConnectionQueueDispatcherRoutingTest` for this engine.
 *
 * Like NWConnection, Netty has no `register` + `kevent`/`epoll_ctl`
 * funnel; its single-thread invariant is enforced by funnelling all
 * coroutine resumptions onto the channel's Netty `EventLoop` via
 * [NettyEventLoopDispatcher] (`NettyIoTransport.ioDispatcher`). That is
 * why `NettyIoTransport.flush` / `write` mutate `pendingWrites` /
 * `pendingBytes` without an explicit `if (inEventLoop())` guard — they
 * are only ever invoked from coroutines already dispatched onto the
 * EventLoop. The POSIX engines' funnel decision
 * `if (inEventLoop()) inline else dispatch`
 * is here the dispatcher's [NettyEventLoopDispatcher.isDispatchNeeded]
 * (`!eventLoop.inEventLoop()`) plus
 * [NettyEventLoopDispatcher.dispatch] (`eventLoop.execute(block)`).
 *
 * (`NettyIoTransport.close` carries a second, thread-agnostic funnel for
 * the close path — `if (loop.inEventLoop()) … else loop.execute { … }`
 * — exercised indirectly by the engine lifecycle tests; this file pins
 * the primary dispatcher routing, the analog of the other engines'
 * coverage.)
 *
 * The dispatcher is constructed standalone over a bare Netty
 * `EventLoop` (`NioEventLoopGroup(1).next()`) — no channel / socket
 * needed, mirroring `NwConnectionQueueDispatcherRoutingTest`:
 *
 * - **cross-context → on-EventLoop**: a block submitted through the
 *   dispatcher from off-loop executes on the EventLoop
 *   (`eventLoop.inEventLoop()` is `true` inside it) — the funnel
 *   (dispatch) branch.
 * - **funnel decision**: `isDispatchNeeded` is `true` off-loop and
 *   `false` on-loop (inline elision) — the analog of `inEventLoop()`.
 * - **coroutine-level routing**: `withContext(dispatcher)` from off-loop
 *   runs its body on the EventLoop — the real `ioDispatcher` path.
 */
class NettyEventLoopDispatcherRoutingTest {

    // Hang-detection budget. Each case submits at most one task onto a
    // freshly created Netty EventLoop and awaits a `CompletableDeferred`;
    // `EventLoop.execute` latency is sub-millisecond, so 5 s absorbs CI
    // load while surfacing a real hang quickly. Mirrors
    // NwConnectionQueueDispatcherRoutingTest.
    private val budget = 5.seconds

    /**
     * A block submitted through [NettyEventLoopDispatcher.dispatch] from a
     * thread that is not the Netty EventLoop must execute on that
     * EventLoop — the funnel (dispatch) branch.
     */
    @Test
    fun `dispatch from off-EventLoop routes the block onto the EventLoop`() = runBlocking {
        withTimeout(budget) {
            val group = NioEventLoopGroup(1)
            try {
                val eventLoop = group.next()
                val dispatcher = NettyEventLoopDispatcher(eventLoop)
                val onLoop = CompletableDeferred<Boolean>()
                // Submit via the dispatcher from the test thread (off-loop);
                // it must route the block onto the EventLoop.
                dispatcher.dispatch(
                    EmptyCoroutineContext,
                    Runnable { onLoop.complete(eventLoop.inEventLoop()) },
                )
                assertTrue(
                    onLoop.await(),
                    "dispatcher.dispatch from off-EventLoop must run the block on the EventLoop",
                )
            } finally {
                group.shutdownGracefully()
            }
        }
    }

    /**
     * The funnel decision: [NettyEventLoopDispatcher.isDispatchNeeded] is
     * `true` off-loop (work must be routed) and `false` on-loop (inline
     * elision). The analog of the POSIX engines' `inEventLoop()`.
     */
    @Test
    fun `isDispatchNeeded is true off-EventLoop and false on-EventLoop`() = runBlocking {
        withTimeout(budget) {
            val group = NioEventLoopGroup(1)
            try {
                val eventLoop = group.next()
                val dispatcher = NettyEventLoopDispatcher(eventLoop)

                // Off-loop (test thread): dispatch is needed.
                assertTrue(
                    dispatcher.isDispatchNeeded(EmptyCoroutineContext),
                    "off-EventLoop caller must require dispatch",
                )

                // On-loop: dispatch is elided (run inline).
                val onLoop = CompletableDeferred<Boolean>()
                eventLoop.execute { onLoop.complete(dispatcher.isDispatchNeeded(EmptyCoroutineContext)) }
                assertFalse(
                    onLoop.await(),
                    "on-EventLoop caller must not require dispatch (inline elision)",
                )
            } finally {
                group.shutdownGracefully()
            }
        }
    }

    /**
     * Coroutine-level routing: `withContext(dispatcher)` from off-loop
     * runs its body on the EventLoop — the real usage path through which
     * `NettyIoTransport.ioDispatcher` resumptions land on the EventLoop.
     */
    @Test
    fun `withContext on dispatcher from off-EventLoop runs the body on the EventLoop`() = runBlocking {
        withTimeout(budget) {
            val group = NioEventLoopGroup(1)
            try {
                val eventLoop = group.next()
                val dispatcher = NettyEventLoopDispatcher(eventLoop)
                val ranOnLoop = withContext(dispatcher) { eventLoop.inEventLoop() }
                assertTrue(
                    ranOnLoop,
                    "withContext(dispatcher) from off-EventLoop must run its body on the EventLoop",
                )
            } finally {
                group.shutdownGracefully()
            }
        }
    }
}
