package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.io.BufferAllocator
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.io.BufferedSuspendSource
import io.github.fukusaka.keel.io.NativeBuf
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
 * Engine layer:  read/write(NativeBuf)       0  (zero-copy via unsafePointer)
 * Codec layer:   asSuspendSource/Sink()      0  (NativeBuf direct, zero-copy)
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

    /** Suspends until this channel is fully closed. */
    suspend fun awaitClosed()

    // --- Zero-copy I/O (engine layer) ---

    /**
     * Reads bytes into [buf] starting at its [NativeBuf.writerIndex].
     * Advances [NativeBuf.writerIndex] by the number of bytes read.
     *
     * Engine implementations pass [NativeBuf.unsafePointer] (Native) or
     * [NativeBuf.unsafeBuffer] (JVM) directly to the OS read syscall
     * for zero-copy I/O.
     *
     * @return number of bytes read, or -1 on EOF.
     */
    suspend fun read(buf: NativeBuf): Int

    /**
     * Writes bytes from [buf] between [NativeBuf.readerIndex] and [NativeBuf.writerIndex].
     * Advances [NativeBuf.readerIndex] by the number of bytes consumed.
     * Data is buffered until [flush] is called.
     *
     * The implementation retains [buf] internally and records the byte range,
     * so the caller may reuse or release the buffer after this call returns.
     *
     * @return number of bytes written to the outbound buffer.
     */
    suspend fun write(buf: NativeBuf): Int

    /**
     * Flushes all buffered outbound data to the network.
     *
     * Enables writev/gather-write optimisation when multiple [write]
     * calls precede a single [flush]. Retained buffers are released
     * after the data is sent.
     */
    suspend fun flush()

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
     * Zero-copy: delegates to [read] which writes directly into [NativeBuf].
     * Use [BufferedSuspendSource] to wrap the result for readLine/readByte.
     *
     * Default implementation delegates to [read] via [SuspendChannelSource].
     * Engines can override for specialized implementations (e.g., io_uring).
     */
    fun asSuspendSource(): SuspendSource = SuspendChannelSource(this)

    /**
     * Returns a [SuspendSink] view for writing to this channel.
     *
     * Zero-copy: delegates to [write]/[flush] which read directly from [NativeBuf].
     * Use [BufferedSuspendSink] to wrap the result for writeString/writeByte.
     *
     * Default implementation delegates to [write]/[flush] via [SuspendChannelSink].
     */
    fun asSuspendSink(): SuspendSink = SuspendChannelSink(this)

    /** Closes both read and write sides and releases all resources. */
    override fun close()
}
