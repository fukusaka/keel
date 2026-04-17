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
 * coroutine that runs on it".
 *
 * **Dispatcher invariant**: the engine's own [coroutineContext] does
 * NOT carry a default dispatcher. Callers MUST pass an explicit
 * [kotlinx.coroutines.CoroutineDispatcher] — typically
 * `channel.ioDispatcher` — when invoking [launch]/[async] on this
 * scope. Without an explicit dispatcher the coroutine silently falls
 * back to [kotlinx.coroutines.Dispatchers.Default], which is almost
 * never what the caller wants for I/O work.
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
     * Idempotent: subsequent calls are a no-op.
     */
    suspend fun close()
}
