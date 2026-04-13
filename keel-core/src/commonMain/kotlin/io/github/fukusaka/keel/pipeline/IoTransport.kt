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
 * for shared defaults (appDispatcher, supportsDeferredFlush, awaitPendingFlush,
 * awaitClosed, callback properties).
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
     * Callback invoked when the read side is closed (EOF, error, or
     * connection reset).
     *
     * After this callback, no further [onRead] calls will occur.
     * The transport does NOT call [close] — the callback owner decides
     * whether to close the full connection.
     */
    var onReadClosed: (() -> Unit)?

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
     * The transport retains the buffer (calls [IoBuf.retain]).
     * The caller's readerIndex is advanced immediately so it can
     * reuse the buffer. The buffer is released after flush completes.
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
     * Callback invoked when an async flush completes.
     *
     * Set by the transport's write-readiness callback to signal
     * completion. Used internally by [awaitPendingFlush].
     * Pipeline [HeadHandler] does not set this (fire-and-forget).
     */
    var onFlushComplete: (() -> Unit)?

    /**
     * Suspends until all pending async flush operations complete.
     *
     * Returns immediately if the last [flush] completed synchronously
     * (returned true). Called by Channel mode's
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
     */
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

    // === Properties ===

    /** Buffer allocator for read buffer allocation. */
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
     * Typically the EventLoop thread for poll-based engines (kqueue,
     * epoll, NIO, io_uring) or [kotlinx.coroutines.Dispatchers.Default]
     * for framework-driven engines (Netty, NWConnection).
     */
    val ioDispatcher: CoroutineDispatcher

    /**
     * Dispatcher for application-level coroutines.
     *
     * Typically the same as [ioDispatcher]. NIO overrides this to
     * [kotlinx.coroutines.Dispatchers.Default] because NIO's Selector
     * thread should not run application blocking logic.
     */
    val appDispatcher: CoroutineDispatcher

    /**
     * Whether the transport supports deferred flush (write buffering
     * followed by explicit flush).
     *
     * Most transports return `true`. Node.js returns `false` because
     * `socket.write()` sends immediately.
     */
    val supportsDeferredFlush: Boolean

    companion object {
        /** Default high water mark: 64 KB. Stop writing when this much data is buffered. */
        const val DEFAULT_HIGH_WATER_MARK = 65536

        /** Default low water mark: 32 KB. Resume writing when buffered data drops below this. */
        const val DEFAULT_LOW_WATER_MARK = 32768

        /** Default read buffer size: 8 KiB. Used by pull-model engines for read allocation. */
        const val DEFAULT_READ_BUFFER_SIZE = 8192
    }
}
