package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.io.BufferedSuspendSource
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

    /**
     * The buffer allocator for this channel.
     *
     * Buffers are cheapest to allocate and release on this channel's
     * EventLoop: with the default pooled allocator the owning EventLoop hits a
     * lock-free freelist fast path. A release from another thread is still safe
     * (the pooled allocator routes it to the owning context via its
     * confinement) but takes the slower cross-context path.
     */
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
     * **Ownership (transfer)**: this method takes ownership of [buf] from the
     * caller. The caller must not touch the buffer after this call returns —
     * no further read/write, no [IoBuf.release], and no index inspection.
     * The transport releases the buffer after [flush] completes (or on teardown).
     * If the caller wants to keep a reference alive (for example, to write the
     * same data to multiple channels), it must call [IoBuf.retain] **before**
     * passing the buffer in.
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
     *
     * **May raise.** A send the platform refused outright is a failure, not a
     * completed flush, and the bytes it was carrying are gone.
     *
     * **Which call sees it is the call that ran the drain that hit it**, and
     * no call is guaranteed to be that call: this one when it drains in
     * place, [awaitFlushComplete] when the drain was deferred and this waiter
     * reached it first, and neither when a scheduled drain got there first —
     * that path contains the failure and ends the connection. Treat the
     * failure as something to handle where it surfaces, not as something to
     * ask after.
     *
     * The readiness engines report it. Among the others only nio does, and
     * only with flush coalescing off — on the default coalescing path it
     * loses the failure to the loop's task guard. netty, nwconnection,
     * nodejs and io_uring do not report it at all, in ways that differ
     * between them; converging them is tracked.
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
        throw UnsupportedOperationException(
            "requestFlush() not implemented. Override flush() or requestFlush()+awaitFlushComplete().",
        )
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
     *
     * **May raise, for the same reason [flush] does**, on two routes: when
     * this waiter reaches a deferred drain before whatever else would run
     * it, and when it re-drives a queue whose previous drain failed with
     * that failure contained elsewhere. Otherwise a finished drain is over —
     * this returns normally, or fails with the cancellation a close
     * installs. It is not a way to ask, after the fact, whether the last
     * flush reached the peer.
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
    val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    // --- Half-close ---

    /**
     * Shuts down the write side of this channel (TCP FIN),
     * signalling that no more output will be sent.
     * The read side remains open for consuming the peer's remaining data.
     *
     * **Safe to call from any thread, and the FIN may be sent after this
     * returns.** Engines that own an EventLoop issue the syscall on that
     * thread, so an off-loop caller only queues the request.
     *
     * Buffered writes are sent first: whatever [write] queued before this
     * call reaches the peer ahead of the FIN, so an explicit [flush] is not
     * required. Writes issued *after* it are discarded — the caller declared
     * it had nothing more to send. Use [awaitFlushComplete] to observe the
     * data leaving; the FIN follows it.
     *
     * Ordering the FIN behind the data also makes it as slow as the data: a
     * peer that stops reading holds both back, bounded only by the engine's
     * idle timeout (disabled by default). Call [close] instead when the FIN
     * has to go out regardless — it supersedes a pending half-close and
     * discards what was still queued.
     *
     * **May raise**, since the buffered writes go first: a caller on the
     * engine's own thread whose half-close drains in place is told they could
     * not be sent. Under flush coalescing — on by default in the readiness
     * engines — the drain runs on a later tick instead, which contains the
     * failure and ends the connection rather than raising here. A caller off
     * the engine's thread does not carry the failure back either way: it
     * queues the request, or — on an engine whose loop has already stopped,
     * where the buffered writes will never drain — is refused and reported
     * there.
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

/**
 * Default [SuspendSource] implementation that delegates to [Channel.read].
 *
 * Used by [Channel.asSuspendSource]'s default implementation. Engines can
 * override [Channel.asSuspendSource] to provide a specialized implementation
 * (e.g., io_uring completion-based reads) without changing this class.
 */
private class SuspendChannelSource(private val channel: Channel) : SuspendSource {
    override suspend fun read(buf: IoBuf): Int = channel.read(buf)

    /** No-op: channel lifecycle is managed by the caller, not by this source. */
    override fun close() {}
}

/**
 * Default [SuspendSink] implementation that delegates to [Channel.write]/[Channel.flush].
 *
 * Used by [Channel.asSuspendSink]'s default implementation. Engines can
 * override [Channel.asSuspendSink] to provide a specialized implementation
 * without changing this class.
 */
private class SuspendChannelSink(private val channel: Channel) : SuspendSink {
    override suspend fun write(buf: IoBuf): Int = channel.write(buf)
    override suspend fun flush() = channel.flush()

    /** No-op: channel lifecycle is managed by the caller, not by this sink. */
    override fun close() {}
}
