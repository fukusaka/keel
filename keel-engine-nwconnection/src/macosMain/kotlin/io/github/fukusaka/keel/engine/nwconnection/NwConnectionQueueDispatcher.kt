package io.github.fukusaka.keel.engine.nwconnection

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_queue_t
import kotlin.coroutines.CoroutineContext

/**
 * [CoroutineDispatcher] that runs every dispatched task on a GCD
 * dispatch queue — specifically the per-connection serial
 * `dispatch_queue_t` that [NwIoTransport] already uses for NWConnection
 * read / write / state-change callbacks.
 *
 * **Why this exists**: [SuspendBridgeHandler]
 * (in `keel-core`) has a documented single-thread invariant — all
 * methods (`onRead`, `onInactive`, `read`, `write`, `flush`) must run
 * on the same thread. Other engines satisfy this trivially because
 * their `ioDispatcher` *is* their EventLoop (epoll / kqueue /
 * io_uring / nio all set `ioDispatcher = eventLoop`).
 *
 * NWConnection has no EventLoop object; its native threading model
 * is "one serial dispatch queue per connection, all callbacks
 * serialised on that queue". Previously `NwIoTransport.ioDispatcher`
 * pointed at `Dispatchers.Default`, so coroutine-side `withContext`
 * hops ran on a Default worker thread while NWConnection callbacks
 * ran on the dispatch queue — two different threads touching
 * `SuspendBridgeHandler.readCont` / `readQueue` / `eof` with no
 * memory-visibility guarantees. Kotlin/Native's concurrent GC
 * deterministically exposed the race as a cycle-13 stall in
 * `NwEngineTest.GC heap size does not grow after repeated echo cycles`
 * on GitHub Actions `macos-latest`.
 *
 * By pointing `ioDispatcher` at the same dispatch queue NWConnection
 * callbacks already use, coroutine resumptions and native callbacks
 * run on a single serial queue, matching every other engine's
 * invariant. No bridge-level atomics are needed.
 *
 * **Dispatch elision**: [isDispatchNeeded] uses
 * `dispatch_queue_set_specific` / `dispatch_get_specific` to detect
 * that the caller is already running on [queue], in which case the
 * coroutine runtime skips the `dispatch_async` entirely and executes
 * the block inline. This matters for the common case of a
 * `withContext(ioDispatcher) { … }` hop whose caller was already
 * resumed on [queue] by a NWConnection callback — the hop becomes a
 * no-op and avoids paying one `dispatch_async` round-trip on every
 * `PipelinedChannel.read` / `write` / `flush`. Without this
 * optimisation the per-op dispatch overhead shows up as a ~4%
 * throughput regression and ~50% higher p99 latency on loopback
 * `/hello` vs `Dispatchers.Default`.
 *
 * **Implementation**: [dispatch] forwards to GCD via `dispatch_async`.
 * Kotlin/Native bridges the Kotlin closure to an Objective-C block
 * automatically. Exceptions thrown from the dispatched block propagate
 * through the coroutine runtime the same way they do on other
 * [CoroutineDispatcher] implementations.
 *
 * **Lifetime**: the queue is owned by [NwIoTransport]; this dispatcher
 * holds a reference to keep the queue alive for as long as any
 * pending coroutine continuation exists. When the transport closes,
 * outstanding dispatched blocks drain on the queue before the queue
 * is deallocated. The [markerRef] [StableRef] backs a unique
 * per-dispatcher pointer used as the specific key; it is intentionally
 * never disposed — its lifetime matches the dispatcher, which matches
 * the queue, so the leak is bounded by connection count and cleaned
 * up when the process exits.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwConnectionQueueDispatcher(
    private val queue: dispatch_queue_t,
) : CoroutineDispatcher() {

    // Per-dispatcher unique marker. Pointer identity is what matters —
    // the underlying object is just an Any() placeholder. dispatch queue
    // specifics need a unique void* key per slot, so one StableRef per
    // dispatcher guarantees uniqueness without a global registry.
    private val markerRef: StableRef<Any> = StableRef.create(Any())
    private val marker: COpaquePointer = markerRef.asCPointer()

    init {
        // Tag [queue] with [marker] → [marker]. From inside any block
        // executing on [queue], `dispatch_get_specific(marker)` returns
        // [marker]; from other queues it returns NULL. This is the
        // standard "am I on this queue?" check documented by Apple as
        // the replacement for the deprecated `dispatch_get_current_queue`.
        dispatch_queue_set_specific(queue, marker, marker, null)
    }

    override fun isDispatchNeeded(context: CoroutineContext): Boolean {
        // If we are already executing on [queue], the coroutine runtime
        // can run the block inline — no `dispatch_async` round-trip
        // needed. This elides the typical NWConnection callback → coroutine
        // hop where the caller is already on the right thread.
        return dispatch_get_specific(marker) != marker
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        dispatch_async(queue) {
            block.run()
        }
    }
}
