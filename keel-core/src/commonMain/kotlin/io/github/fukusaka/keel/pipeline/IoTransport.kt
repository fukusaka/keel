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
 * awaitClosed).
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
 * excessive buffering. Implementations maintain a `pendingBytes` counter
 * incremented on [write] and decremented on flush completion. When
 * `pendingBytes >= highWaterMark`, [isWritable] becomes false; when
 * `pendingBytes < lowWaterMark`, it becomes true again.
 */
interface IoTransport {

    // === Read path ===

    /** Callback invoked when inbound data arrives. */
    var onRead: ((IoBuf) -> Unit)?

    /** Callback invoked when the read side is closed (EOF, error, or connection reset). */
    var onReadClosed: (() -> Unit)?

    /** Enables or disables the read loop. Initial value: `false`. */
    var readEnabled: Boolean

    // === Write path ===

    /** Buffers [buf] for a subsequent [flush]. Retains the buffer. */
    fun write(buf: IoBuf)

    /** Sends all buffered writes to the network. Returns true if completed synchronously. */
    fun flush(): Boolean

    /** Callback invoked when an async flush completes. */
    var onFlushComplete: (() -> Unit)?

    /** Suspends until all pending async flush operations complete. */
    suspend fun awaitPendingFlush()

    /** Whether the transport can accept more writes without excessive buffering. */
    val isWritable: Boolean

    /** Callback invoked when [isWritable] changes state. */
    var onWritabilityChanged: ((Boolean) -> Unit)?

    // === Lifecycle ===

    /** Sends TCP FIN to the peer (half-close). Idempotent. */
    fun shutdownOutput()

    /** Closes the transport and releases all resources. Idempotent. */
    fun close()

    /** Suspends until the transport is fully closed. */
    suspend fun awaitClosed()

    // === Properties ===

    /** Buffer allocator for read operations. */
    val allocator: BufferAllocator

    /** Whether the transport is open (not yet closed). */
    val isOpen: Boolean

    /** Dispatcher for I/O operations on this transport. */
    val ioDispatcher: CoroutineDispatcher

    /** Dispatcher for application-level coroutines. */
    val appDispatcher: CoroutineDispatcher

    /** Whether the transport supports deferred flush. */
    val supportsDeferredFlush: Boolean

    companion object {
        const val DEFAULT_HIGH_WATER_MARK = 65536
        const val DEFAULT_LOW_WATER_MARK = 32768
        const val DEFAULT_READ_BUFFER_SIZE = 8192
    }
}
