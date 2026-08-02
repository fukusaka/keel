package io.github.fukusaka.keel.engine.nwconnection

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pin the **I/O ownership invariant funnel routing** for NWConnection —
 * the equivalent of `KqueueEventLoopFunnelSeamTest` /
 * `EpollEventLoopFunnelSeamTest` for this engine.
 *
 * NWConnection has no `register` + `kevent`/`epoll_ctl` funnel; its
 * single-thread invariant is enforced by funnelling all coroutine
 * resumptions and callbacks onto the per-connection GCD serial queue via
 * [NwConnectionQueueDispatcher]. The POSIX engines' funnel decision
 * `if (inEventLoop()) inline else dispatch`
 * is here the dispatcher's [NwConnectionQueueDispatcher.isDispatchNeeded]
 * (`dispatch_get_specific(marker) != marker`) plus
 * [NwConnectionQueueDispatcher.dispatch] (`dispatch_async(queue)`).
 *
 * The sibling [NwConnectionQueueDispatcherAssertTest] pins the
 * `assertInConnectionQueue` *contract* (passes on-queue, fails
 * off-queue) but drives the queue with raw `dispatch_async` — it never
 * exercises the dispatcher's own routing. This file pins that routing:
 *
 * - **cross-context → on-queue**: a block submitted through the
 *   dispatcher from off-queue must execute *on* the connection queue
 *   (`assertInConnectionQueue` succeeds inside it) — the funnel
 *   (dispatch) branch.
 * - **funnel decision**: [isDispatchNeeded] is `true` off-queue (work
 *   must be routed) and `false` on-queue (inline elision) — the analog
 *   of `inEventLoop()`.
 * - **coroutine-level routing**: `withContext(dispatcher)` from
 *   off-queue runs its body on the connection queue, the real usage
 *   path (`NwIoTransport.ioDispatcher` resumptions).
 */
@OptIn(ExperimentalForeignApi::class)
class NwConnectionQueueDispatcherRoutingTest {

    // Hang-detection budget. Each case dispatches at most one block onto a
    // freshly created GCD serial queue and awaits a `CompletableDeferred`;
    // loopback `dispatch_async` latency is well under 10 ms, so 5 s
    // absorbs CI load while surfacing a real hang quickly. Mirrors
    // NwConnectionQueueDispatcherAssertTest.
    private val asyncBudget = 5.seconds

    /**
     * A block submitted through [NwConnectionQueueDispatcher.dispatch]
     * from a thread that is *not* on the connection queue must execute on
     * that queue — the funnel (dispatch) branch.
     */
    @Test
    fun `dispatch from off-queue routes the block onto the connection queue`() = runBlocking {
        withTimeout(asyncBudget) {
            val queue = dispatch_queue_create("io.github.fukusaka.keel.test.routing-dispatch", null)
                ?: error("dispatch_queue_create returned null")
            val dispatcher = NwConnectionQueueDispatcher(queue)
            val done = CompletableDeferred<Result<Unit>>()
            // Submit via the dispatcher (NOT raw dispatch_async). We are on
            // the test thread = off-queue, so the dispatcher must route the
            // block onto the connection queue, where assertInConnectionQueue
            // passes.
            dispatcher.dispatch(
                EmptyCoroutineContext,
                Runnable {
                    done.complete(runCatching { dispatcher.assertInConnectionQueue("routing.dispatch") })
                },
            )
            assertTrue(
                done.await().isSuccess,
                "dispatcher.dispatch from off-queue must route the block onto the connection queue",
            )
        }
    }

    /**
     * The funnel decision: [NwConnectionQueueDispatcher.isDispatchNeeded]
     * is `true` off-queue (work must be routed) and `false` on-queue
     * (inline elision). The analog of the POSIX engines' `inEventLoop()`.
     */
    @Test
    fun `isDispatchNeeded is true off-queue and false on-queue`() = runBlocking {
        withTimeout(asyncBudget) {
            val queue = dispatch_queue_create("io.github.fukusaka.keel.test.routing-decision", null)
                ?: error("dispatch_queue_create returned null")
            val dispatcher = NwConnectionQueueDispatcher(queue)

            // Off-queue (test thread): dispatch is needed.
            assertTrue(
                dispatcher.isDispatchNeeded(EmptyCoroutineContext),
                "off-queue caller must require dispatch",
            )

            // On-queue: dispatch is elided (run inline).
            val onQueue = CompletableDeferred<Boolean>()
            dispatch_async(queue) {
                onQueue.complete(dispatcher.isDispatchNeeded(EmptyCoroutineContext))
            }
            assertFalse(
                onQueue.await(),
                "on-queue caller must not require dispatch (inline elision)",
            )
        }
    }

    /**
     * Coroutine-level routing: `withContext(dispatcher)` from off-queue
     * runs its body on the connection queue — the real usage path through
     * which `NwIoTransport.ioDispatcher` resumptions land on the queue.
     */
    @Test
    fun `withContext on dispatcher from off-queue runs the body on the connection queue`() = runBlocking {
        withTimeout(asyncBudget) {
            val queue = dispatch_queue_create("io.github.fukusaka.keel.test.routing-withContext", null)
                ?: error("dispatch_queue_create returned null")
            val dispatcher = NwConnectionQueueDispatcher(queue)
            val ranOnQueue = withContext(dispatcher) {
                runCatching { dispatcher.assertInConnectionQueue("routing.withContext") }.isSuccess
            }
            assertTrue(
                ranOnQueue,
                "withContext(dispatcher) from off-queue must run its body on the connection queue",
            )
        }
    }
}
