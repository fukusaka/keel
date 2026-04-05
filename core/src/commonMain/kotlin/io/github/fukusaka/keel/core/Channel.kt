package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.io.BufferedSuspendSource
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.io.OwnedSuspendSource
import io.github.fukusaka.keel.io.SuspendSink
import io.github.fukusaka.keel.io.SuspendSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * A bidirectional byte channel backed by a network connection.
 *
 * ```
 * Layer          API                       Copy
 * -----          ---                       ----
 * Engine layer:  read/write(IoBuf)       0  (zero-copy via unsafePointer)
 * Codec layer:   asSuspendSource/Sink()      0  (IoBuf direct, zero-copy)
 * ```
 *
 * **Write/flush separation**: [write] buffers data without sending.
 * [flush] sends all buffered data to the network. This enables
 * writev/gather-write optimisation when multiple writes precede
 * a single flush.
 *
 * **Half-close**: only [shutdownOutput] is provided.
 * Input-side EOF is detected by [read] returning -1.
 * [shutdownInput] was omitted (YAGNI): Netty/Go use it only in tests,
 * and NWConnection/Node.js have no explicit support.
 *
 * **Lifecycle**: [isOpen] tracks whether the fd is still open.
 * [isActive] tracks whether the channel is connected and ready for I/O.
 * Both become false after [close].
 */
interface Channel : AutoCloseable {

    /** Buffer allocator associated with this channel's engine. */
    val allocator: BufferAllocator

    /** Remote address of the peer, or null if not connected. */
    val remoteAddress: SocketAddress?

    /** Local address this channel is bound to, or null if not bound. */
    val localAddress: SocketAddress?

    // --- Lifecycle ---

    /** True if the underlying transport is open (not yet fully closed). */
    val isOpen: Boolean

    /** True if the channel is connected and ready for read/write. */
    val isActive: Boolean

    /**
     * Suspends until this channel is fully closed.
     * Returns immediately if the channel is already closed.
     */
    suspend fun awaitClosed()

    // --- Zero-copy I/O (engine layer) ---

    /**
     * Reads bytes into [buf] starting at its [IoBuf.writerIndex].
     * Advances [IoBuf.writerIndex] by the number of bytes read.
     *
     * Engine implementations pass [IoBuf.unsafePointer] (Native) or
     * [IoBuf.unsafeBuffer] (JVM) directly to the OS read syscall
     * for zero-copy I/O.
     *
     * @return number of bytes read, or -1 on EOF.
     */
    suspend fun read(buf: IoBuf): Int

    /**
     * Writes bytes from [buf] between [IoBuf.readerIndex] and [IoBuf.writerIndex].
     * Advances [IoBuf.readerIndex] by the number of bytes consumed.
     * Data is buffered until [flush] is called.
     *
     * **Ownership**: the implementation calls [IoBuf.retain] on [buf] and
     * records the byte range. [flush] releases the retained reference after
     * writing. The caller may reuse or release the buffer after this call returns.
     *
     * @return number of bytes written to the outbound buffer.
     */
    suspend fun write(buf: IoBuf): Int

    /**
     * Flushes all buffered outbound data to the network and suspends
     * until all bytes are sent.
     *
     * Default implementation calls [requestFlush] + [awaitFlushComplete].
     * Engines that override this directly (e.g., Netty, NWConnection) do
     * not need to implement [requestFlush]/[awaitFlushComplete].
     *
     * For fire-and-forget flushing (no completion wait), call
     * [requestFlush] directly.
     */
    suspend fun flush() {
        requestFlush()
        awaitFlushComplete()
    }

    /**
     * Initiates a flush of all buffered outbound data without waiting
     * for completion (fire-and-forget).
     *
     * Data is submitted to the OS send buffer (or queued for async
     * send on EAGAIN). Use [awaitFlushComplete] to wait for all
     * pending data to be sent.
     *
     * Default: throws [UnsupportedOperationException]. Engines that use
     * the [requestFlush] + [awaitFlushComplete] pattern must override.
     * Engines that override [flush] directly do not need this.
     */
    fun requestFlush() {
        throw UnsupportedOperationException("requestFlush() not implemented. Override flush() or requestFlush()+awaitFlushComplete().")
    }

    /**
     * Suspends until all pending flush operations complete.
     *
     * Returns immediately if no async flush is pending (i.e., the
     * last [requestFlush] completed synchronously).
     *
     * Default: no-op (assumes [flush] override handles completion).
     * Engines that use the [requestFlush] + [awaitFlushComplete] pattern
     * must override.
     */
    suspend fun awaitFlushComplete() {}

    // --- Dispatcher ---

    /**
     * The [CoroutineDispatcher] best suited for I/O operations on this channel.
     *
     * Engines with dedicated EventLoop threads (NIO, kqueue, epoll) return
     * their EventLoop's dispatcher, enabling coroutines to run on the same
     * thread that drives I/O — eliminating cross-thread dispatch overhead.
     *
     * Default: [Dispatchers.Default] for engines without a dedicated EventLoop.
     * (Dispatchers.IO is not available in commonMain due to JS target.)
     */
    val coroutineDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    /**
     * The [CoroutineDispatcher] for application-level processing (e.g. Ktor pipeline).
     *
     * Native engines (kqueue, epoll) return the EventLoop dispatcher (same as
     * [coroutineDispatcher]), running the entire request pipeline on the I/O
     * thread — same model as Netty. JVM NIO returns [Dispatchers.Default] to
     * leverage ForkJoinPool work-stealing for better load distribution.
     *
     * Default: same as [coroutineDispatcher].
     */
    val appDispatcher: CoroutineDispatcher get() = coroutineDispatcher

    // --- Flush strategy ---

    /**
     * Whether this channel supports deferred flushing in [BufferedSuspendSink].
     *
     * When true, [BufferedSuspendSink] enqueues filled buffers without OS write,
     * deferring the actual I/O to the caller's flush(). This enables writev()
     * batching and avoids blocking the EventLoop on per-buffer OS writes.
     *
     * Requires that write() and flush() run on the same single thread (EventLoop).
     * Push-model engines (Netty, NWConnection, Node.js) must return false because
     * their flush runs on a different thread, making pool-based buffer recycling
     * unsafe.
     *
     * Default: false (safe for all engines).
     */
    val supportsDeferredFlush: Boolean get() = false

    // --- Half-close ---

    /**
     * Shuts down the write side of this channel (TCP FIN),
     * signalling that no more output will be sent.
     * The read side remains open for consuming the peer's remaining data.
     */
    fun shutdownOutput()

    // --- Suspend I/O bridge (codec layer, zero-copy) ---

    /**
     * Returns a [SuspendSource] view for reading from this channel.
     *
     * Zero-copy: delegates to [read] which writes directly into [IoBuf].
     * Use [BufferedSuspendSource] to wrap the result for readLine/readByte.
     *
     * Default implementation delegates to [read] via [SuspendChannelSource].
     * Engines can override for specialized implementations (e.g., io_uring).
     */
    fun asSuspendSource(): SuspendSource = SuspendChannelSource(this)

    /**
     * Returns a [SuspendSink] view for writing to this channel.
     *
     * Zero-copy: delegates to [write]/[flush] which read directly from [IoBuf].
     * Use [BufferedSuspendSink] to wrap the result for writeString/writeByte.
     *
     * Default implementation delegates to [write]/[flush] via [SuspendChannelSink].
     */
    fun asSuspendSink(): SuspendSink = SuspendChannelSink(this)

    /**
     * Returns a [BufferedSuspendSource] for codec-layer reading.
     *
     * Default implementation uses pull-mode (allocates internal buffer, 1 copy per read).
     * [PipelinedChannel] overrides to use push-mode via [SuspendBridgeHandler]'s
     * [OwnedSuspendSource], achieving zero-copy.
     */
    fun asBufferedSuspendSource(): BufferedSuspendSource =
        BufferedSuspendSource(asSuspendSource(), allocator)

    /** Closes both read and write sides and releases all resources. */
    override fun close()
}
