package io.github.fukusaka.keel.core

import kotlinx.coroutines.CoroutineScope

/**
 * Root interface for all keel I/O engines.
 *
 * Provides access to the engine's [config] and lifecycle management
 * ([close]). Concrete transport models are defined by sub-interfaces:
 *
 * - [StreamEngine]: byte-stream transport (TCP, Unix SOCK_STREAM)
 * - `DatagramEngine` (future): message-oriented transport (UDP, Unix SOCK_DGRAM)
 *
 * ```
 * IoEngine (config + close, CoroutineScope)
 * ├── StreamEngine    ← TCP, Unix SOCK_STREAM, QUIC
 * └── DatagramEngine  ← UDP, Unix SOCK_DGRAM (future)
 * ```
 *
 * Engine implementations typically implement [StreamEngine] (and
 * optionally `DatagramEngine` when UDP support is added):
 *
 * ```
 * class EpollEngine : StreamEngine { ... }
 * ```
 *
 * **CoroutineScope**: `IoEngine` is a [CoroutineScope] whose
 * [coroutineContext] carries a [kotlinx.coroutines.SupervisorJob]. All
 * coroutines that dispatch I/O onto channels created by this engine
 * SHOULD be launched as children of this scope — i.e., via
 * `ioEngine.launch(channel.ioDispatcher) { ... }`. On [close], the
 * engine cancels every such child and joins its completion before
 * shutting the underlying dispatcher threads down, preserving the
 * structured-concurrency invariant "a dispatcher outlives every
 * coroutine that runs on it" — for a caller that lives long enough to
 * see the join through. What happens when it does not is on [close].
 *
 * **Dispatcher invariant**: the engine's own [coroutineContext] does
 * NOT carry a default dispatcher. Callers MUST pass an explicit
 * [kotlinx.coroutines.CoroutineDispatcher] — typically
 * `channel.ioDispatcher` — when invoking [launch]/[async] on this
 * scope. Without an explicit dispatcher the coroutine silently falls
 * back to [kotlinx.coroutines.Dispatchers.Default], which is almost
 * never what the caller wants for I/O work.
 *
 * **Resource-sharing invariant** (cross-engine contract): every
 * `bind` / `bindPipeline` call — and therefore every server — on one
 * engine shares the engine's accept and worker event loops and the
 * engine-owned allocator. Creating several servers multiplies only
 * the listen sockets and the callers' own per-server state, never the
 * thread or buffer-pool footprint: child allocators scale with event
 * loops or connections (engine-specific), never with server count.
 * The engine must outlive every server and connection it produced;
 * [close] is the owner's final call after they are all closed. There
 * is no atomicity between separate bind calls — each call is its own
 * unit (the multi-address `bindPipeline` overload is the
 * all-or-nothing unit for one server on several addresses).
 *
 * **I/O ownership invariant** (cross-engine contract): every keel
 * engine MUST execute all callbacks, state mutations, and coroutine
 * resumptions for a given channel on a single owning thread (or a
 * single serial queue equivalent), in FIFO order. This is the
 * "strict single-thread per loop + cross-thread funnel" property
 * that lets engines avoid locking on `pendingWrites`, `pendingBytes`,
 * read-buffer slots, registration tables, and similar single-writer
 * state. Implementations choose one of two enforcement mechanisms:
 *
 * - **Explicit funnel** (POSIX-style: `engine-kqueue`, `engine-epoll`,
 *   `engine-nio`, `engine-io-uring`, `engine-netty`). The engine owns
 *   an `EventLoop` thread and exposes a funnelled entry point of the
 *   shape `if (inEventLoop()) apply else dispatch(Runnable)`. Any
 *   off-loop caller marshals work onto the loop thread through the
 *   dispatcher. Inner helpers carry an `assertInEventLoop` contract
 *   so accidental bypass fails fast.
 * - **Upstream-delegated** (`engine-nwconnection`, `engine-nodejs`,
 *   `engine-netty` via Netty's own `EventLoop`). The underlying
 *   runtime (GCD serial `dispatch_queue` / Node.js libuv event loop
 *   / Netty `SingleThreadEventLoop`) guarantees serial execution at
 *   the framework level, so no application-level funnel is needed.
 *   Where the runtime exposes a queue / thread identity check
 *   (`dispatch_get_specific`), the engine still installs an
 *   `assertIn…Queue` analog of `assertInEventLoop` on callback
 *   entries so future maintainers wiring up new callback sites get
 *   the same fail-fast contract.
 *
 * The invariant is the same in both cases; only the enforcement
 * mechanism differs.
 *
 * @see StreamEngine
 * @see IoEngineConfig
 */
interface IoEngine : CoroutineScope {

    /** Engine-wide configuration (allocator, threads, logging). */
    val config: IoEngineConfig

    /**
     * Closes the engine: cancels every child coroutine launched on this
     * scope, joins their completion, and tears down the underlying
     * dispatcher threads and OS resources (kqueue fd, selector, etc.).
     *
     * The order is deliberately "cancel-and-join children, then close
     * dispatchers". Children suspended on engine dispatchers observe
     * cancellation via the normal kotlinx.coroutines resume path while
     * their dispatcher is still alive, so every continuation unwinds
     * through `CancellationException`.
     *
     * **The join belongs to the caller.** It is a suspension point, so it
     * answers to the calling coroutine's job rather than the engine's, and
     * a caller that is cancelled — or that runs out of its own timeout —
     * loses it. What each implementation does then differs, and the
     * difference matters because this call commits before it does the
     * work: whichever implementation it is, a second caller is told the
     * engine is closed and returns.
     *
     * The epoll and kqueue engines release their dispatcher threads and OS
     * resources anyway; what that costs is written on their own close. The
     * five that do their release after an unguarded join — NIO, Netty,
     * io_uring, Node.js and Network.framework — skip it entirely, and
     * because the flag is already set nobody can ask again, so whatever
     * each of them holds is held until the process ends. Only an
     * implementation with no suspension point at all is indifferent to
     * this. Prefer letting this call finish.
     *
     * Idempotent: subsequent calls are a no-op.
     */
    suspend fun close()
}
