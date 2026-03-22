package io.github.fukusaka.keel.core

import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * A bidirectional byte channel backed by a network connection.
 *
 * ```
 * Layer          API                  Copy
 * -----          ---                  ----
 * Engine layer:  read/write(NativeBuf)  0  (zero-copy via unsafePointer)
 * Codec layer:   asSource/asSink()      1  (NativeBuf <-> kotlinx-io Buffer)
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

    // --- Half-close ---

    /**
     * Shuts down the write side of this channel (TCP FIN),
     * signalling that no more output will be sent.
     * The read side remains open for consuming the peer's remaining data.
     */
    fun shutdownOutput()

    // --- kotlinx-io bridge (codec layer) ---

    /**
     * Returns a [RawSource] view for reading from this channel.
     *
     * Involves a byte-by-byte copy between [NativeBuf] and kotlinx-io [Buffer].
     * Acceptable for codec layer; engine layer should use [read] directly.
     */
    fun asSource(): RawSource

    /**
     * Returns a [RawSink] view for writing to this channel.
     *
     * Involves a byte-by-byte copy between kotlinx-io [Buffer] and [NativeBuf].
     * Acceptable for codec layer; engine layer should use [write]/[flush] directly.
     */
    fun asSink(): RawSink

    /** Closes both read and write sides and releases all resources. */
    override fun close()
}
