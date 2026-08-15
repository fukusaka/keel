package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Transport-layer I/O operations for a single TCP connection.
 *
 * Owns the underlying file descriptor / socket / connection object and
 * provides read, write, shutdown, and lifecycle management. Engine
 * implementations extract all platform-specific I/O logic into an
 * IoTransport so that [AbstractPipelinedChannel] and [HeadHandler] use
 * the same code path across all engines.
 *
 * ```
 * Read path (transport → pipeline):
 *   readEnabled = true → platform event registration
 *   → data arrives → onRead(buf)
 *   → EOF/error   → onReadClosed()
 *
 * Write path (pipeline → transport):
 *   write(buf) → flush() → platform syscall
 *   → EAGAIN → async retry → onFlushComplete()
 * ```
 *
 * All methods must be called on the [ioDispatcher] thread unless
 * otherwise noted.
 *
 * Pure interface — no default implementations. Use [AbstractIoTransport]
 * for shared defaults (awaitPendingFlush, awaitClosed, callback properties).
 *
 * ## Read Path
 *
 * Set [onRead] and [onReadClosed] callbacks, then set [readEnabled] to
 * `true` to start the read loop. The transport handles buffer allocation,
 * platform syscalls (or async callbacks), EAGAIN retry, and automatic
 * re-arming internally. EOF and errors are reported via [onReadClosed].
 *
 * ## Write Backpressure
 *
 * [isWritable] tracks whether the transport can accept more writes without
 * excessive buffering. [AbstractIoTransport] maintains a pending byte
 * counter incremented on [write] and decremented on flush completion.
 * When pending bytes reach [DEFAULT_HIGH_WATER_MARK], [isWritable]
 * becomes false; when they drop below [DEFAULT_LOW_WATER_MARK], it
 * becomes true again.
 */
interface IoTransport {

    // === Read path ===

    /**
     * Callback invoked when inbound data arrives.
     *
     * The transport allocates the buffer, fills it with received data,
     * and invokes this callback. The callback takes ownership of the
     * buffer (must release it when done). After invoking [onRead], the
     * transport automatically re-arms for the next read.
     *
     * Set before [readEnabled] = true. Not called after [close].
     */
    var onRead: ((IoBuf) -> Unit)?

    /**
     * Pauses inbound consumption for flow control (read-side
     * back-pressure). Contract for every engine: stop consuming new
     * bytes from the underlying source within a bounded overshoot (at
     * most the in-flight delivery), so the kernel/framework receive
     * buffer fills and TCP flow control reaches the peer. No data may
     * be dropped. Peer-FIN detection may be delayed while paused.
     *
     * Distinct from [readEnabled], which expresses read *interest* and
     * is mediated by [io.github.fukusaka.keel.core.IdleReadPolicy]
     * (under `DETECT_PEER_CLOSE` the read primitive stays armed when
     * `readEnabled = false`); [pauseReads] must stop consumption
     * regardless of the policy. The default delegates to
     * `readEnabled = false`, which is the correct pause on engines
     * whose disabled read already stops consuming; engines where it
     * does not (policy-armed push engines) override with a real
     * disarm. Must be called on the transport's I/O thread.
     */
    fun pauseReads() {
        readEnabled = false
    }

    /**
     * Resumes inbound consumption after [pauseReads]. Must be called on
     * the transport's I/O thread.
     */
    fun resumeReads() {
        readEnabled = true
    }

    /**
     * Callback invoked when the read side is closed (EOF, error, or
     * connection reset).
     *
     * After this callback, no further [onRead] calls will occur.
     * The transport does NOT call [close] — the callback owner decides
     * whether to close the full connection.
     */
    var onReadClosed: (() -> Unit)?

    /**
     * Hook invoked by [io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel]
     * after [onRead], [onReadClosed], and [onWritabilityChanged] have all
     * been wired up. Engines that pre-arm their read primitive when the
     * caller selects [io.github.fukusaka.keel.core.IdleReadPolicy.DETECT_PEER_CLOSE]
     * should arm here (rather than in `init { }`) so any inbound bytes
     * the platform delivers via the read primitive's first event always
     * observe a non-null [onRead] — arming in `init { }` exposes a race
     * with the channel-construction sequence (`Engine.IoTransport(...)` →
     * `Engine.PipelinedChannel(...)` → `AbstractPipelinedChannel.init`)
     * where the platform's EventLoop / dispatch queue can deliver the
     * first read event before the channel constructor has finished
     * setting the callbacks.
     *
     * Also where the POSIX engines join their EventLoop's participant
     * registry. That registry decides who is told the loop stopped, and each
     * participant is told once — so joining before the callbacks exist spends
     * that one notification on a null and the connection never learns. For
     * those engines this hook is load-bearing, not optional.
     *
     * Default no-op for engines that arm read lazily on `readEnabled =
     * true` (the [io.github.fukusaka.keel.core.IdleReadPolicy.PRESERVE_BACKPRESSURE]
     * path) or that already deliver `onReadClosed` through a separate
     * channel (engine-nodejs / native netty transports).
     */
    fun onChannelAttached() {}

    /**
     * Enables or disables the read loop.
     *
     * When set to `true`, the transport registers platform-specific read
     * interest (kqueue EVFILT_READ, epoll EPOLLIN, NIO OP_READ, io_uring
     * multishot RECV, etc.) and starts delivering data via [onRead].
     *
     * When set to `false`, the transport deregisters read interest. Useful
     * for backpressure: stop reading when the pipeline is overloaded.
     *
     * Initial value: `false` (read loop not started until explicitly enabled).
     */
    var readEnabled: Boolean

    // === Write path ===

    /**
     * Buffers [buf] for a subsequent [flush].
     *
     * **Ownership (transfer)**: takes over the caller's reference. The caller
     * must not touch [buf] after this call returns — no further read/write,
     * no [IoBuf.release], and no index inspection. The transport releases
     * the buffer after [flush] completes (or on teardown). To keep a
     * reference alive, the caller must call [IoBuf.retain] **before** passing
     * the buffer in.
     */
    fun write(buf: IoBuf)

    /**
     * Sends all buffered writes to the network.
     *
     * @return true if the flush completed synchronously (all bytes sent),
     *         false if an async send is pending (e.g., EAGAIN, io_uring SQE).
     */
    fun flush(): Boolean

    /**
     * Callback invoked when a pending flush completes.
     *
     * May fire later, from the transport's write-readiness retry or its
     * coalesced drain — or synchronously, from inside a [flush] call whose
     * drain completed on the spot. A completion-driven pump (write the next
     * chunk and flush from here) is bounded on both engine configurations,
     * by different means: a synchronous drain's reentrant flush drains
     * inline without reporting a completion of its own, and a coalesced
     * flush defers to a fresh loop tick instead of recursing. Used
     * internally by [awaitPendingFlush]. Pipeline [HeadHandler] does not set
     * this (fire-and-forget).
     */
    var onFlushComplete: (() -> Unit)?

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately if the last [flush] completed synchronously
     * (returned true). Called by Coroutine mode's
     * [io.github.fukusaka.keel.core.Channel.awaitFlushComplete].
     */
    suspend fun awaitPendingFlush()

    /**
     * Whether the transport can accept more writes without excessive
     * buffering.
     *
     * Becomes false when pending bytes exceed [DEFAULT_HIGH_WATER_MARK],
     * and true again when they drop below [DEFAULT_LOW_WATER_MARK].
     * Applications should check this before writing large responses.
     */
    val isWritable: Boolean

    /**
     * Callback invoked when [isWritable] changes state.
     *
     * Called with `false` when pending bytes cross [DEFAULT_HIGH_WATER_MARK]
     * (stop writing), and `true` when they drop below
     * [DEFAULT_LOW_WATER_MARK] (resume writing).
     */
    var onWritabilityChanged: ((Boolean) -> Unit)?

    // === Lifecycle ===

    /**
     * Sends TCP FIN to the peer (half-close).
     *
     * The read side remains open so the peer's remaining data can be
     * consumed. Implementations must be idempotent.
     *
     * **Safe to call from any thread, and the FIN may be sent after this
     * returns.** Engines that own an EventLoop issue the syscall on that
     * thread, so an off-loop caller only queues the request. Observe the
     * effect through the peer, not through the call returning.
     *
     * Buffered writes are sent first: whatever [write] queued before this
     * call reaches the peer ahead of the FIN. Writes issued *after* it are
     * discarded — the caller declared it had nothing more to send.
     *
     * Ordering the FIN behind the data also makes it as slow as the data: a
     * peer that stops reading holds both back. `idleTimeoutMillis` bounds
     * that (the write-idle timer force-closes a peer that never drains) and
     * is disabled by default. A [close] before the drain finishes supersedes
     * the half-close and discards what was still queued.
     */
    /**
     * True when the caller is already on the context this transport's
     * non-suspend API must be used from — the EventLoop thread, or the serial
     * dispatch queue that stands in for one.
     *
     * Defaults to `true` so a transport that cannot tell — a test double, an
     * engine whose runtime is single-threaded — never trips a check built on
     * it. Engines that own an EventLoop override it.
     *
     * Cheap enough for the write path: a thread identity compare on the
     * thread-backed engines, a single queue-local lookup on the dispatch-queue
     * ones.
     *
     * **A transport that can return `false` must have an [ioDispatcher] that
     * accepts `dispatch`**, because that is where the pipeline routes outbound
     * work it may not run on the caller's thread. `Dispatchers.Unconfined`
     * does not qualify — it rejects `dispatch` outright — which is consistent
     * with the transports that use it never leaving the default `true`.
     */
    val inOwningContext: Boolean get() = true

    /**
     * Whether work handed to [ioDispatcher] will still run.
     *
     * `false` means the owning context has stopped for good: a `dispatch` is
     * accepted by the queue and never drained, so anything the pipeline routes
     * that way — and anything the closure captures, including a buffer whose
     * ownership was transferred — is stranded for the transport's lifetime.
     * The pipeline asks before dispatching so it can release instead.
     *
     * Default `true`, which is the *unchanged* answer rather than the correct
     * one everywhere. It is accurate for a transport whose dispatcher outlives
     * it or which never leaves [inOwningContext]; the other engines whose loops
     * can also stop while their transports stay reachable have simply not been
     * moved onto it yet, and strand the same work meanwhile. Overriding it is
     * what fixes that, engine by engine.
     *
     * This is not the negation of [isOpen]. A transport can be open with a dead
     * dispatcher (its loop stopped, nobody has closed the channel yet) — which
     * is exactly the case worth asking about.
     */
    val canDispatchToOwningContext: Boolean get() = true

    fun shutdownOutput()

    /**
     * Closes the transport and releases all resources.
     *
     * Releases pending write buffers, deregisters events, and closes
     * the underlying fd/socket/connection. Implementations must be
     * idempotent (use [isOpen] flag to guard).
     */
    fun close()

    /**
     * Suspends until the transport is fully closed.
     *
     * Most transports close synchronously and return immediately.
     * Async transports (NWConnection, Netty) may need to wait for
     * pending callbacks or channel futures.
     */
    suspend fun awaitClosed()

    /**
     * Schedules [task] to run once after [delayMillis] on the owning EventLoop, for
     * codec/server-level completion deadlines (e.g. the header-complete timeout).
     * Returns a [TimerHandle] for cancellation when the phase completes in time, or
     * `null` if this transport has no EventLoop timer (test doubles, unwired engines).
     * Reuses the same per-EventLoop timer that backs the idle timeout.
     *
     * Unlike the idle timeout, this deadline is **absolute** — it is not refreshed by
     * I/O progress, so a trickle of bytes cannot defeat it (that is the point: it
     * bounds the time to *complete* a protocol phase, not the gap between bytes).
     *
     * **Thread safety**: must be called on the EventLoop thread (the pipeline handler
     * thread), like the idle-timeout arm/cancel calls.
     */
    fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle? = null

    // === Properties ===

    /**
     * The buffer allocator for this transport's I/O buffers — read buffers, and
     * any write buffers the codec layer allocates. Cheapest on the owning
     * EventLoop (with the default pooled allocator, a lock-free freelist fast
     * path); an off-EventLoop release is safe but takes the slower cross-context
     * path via the allocator's confinement.
     */
    val allocator: BufferAllocator

    /**
     * Whether the transport is open (not yet closed).
     *
     * Becomes false after [close] is called. Used as the idempotent
     * guard for [close] and as the source of truth for
     * [PipelinedChannel.isActive] / [PipelinedChannel.isOpen].
     */
    val isOpen: Boolean

    /**
     * Dispatcher for I/O operations on this transport.
     *
     * Typically the EventLoop thread that drives the engine's native I/O
     * primitive (kqueue / epoll / io_uring pthread, NIO Selector thread,
     * Netty `EventLoop`, GCD dispatch queue, Node.js event loop). Every
     * keel engine resumes coroutines on that same thread so
     * `channel.read` / `write` / `flush` do not cross threads.
     */
    val ioDispatcher: CoroutineDispatcher

    companion object {
        /** Default high water mark: 64 KB. Stop writing when this much data is buffered. */
        const val DEFAULT_HIGH_WATER_MARK = 65536

        /** Default low water mark: 32 KB. Resume writing when buffered data drops below this. */
        const val DEFAULT_LOW_WATER_MARK = 32768

        /** Default read buffer size: 8 KiB. Used by pull-model engines for read allocation. */
        const val DEFAULT_READ_BUFFER_SIZE = 8192
    }
}
