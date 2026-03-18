package io.github.keel.core

import kotlinx.io.RawSink
import kotlinx.io.RawSource

/**
 * A bidirectional byte channel backed by a network connection.
 *
 * Read/write operate directly on [NativeBuf] for zero-copy I/O at the engine layer.
 * Use [asSource]/[asSink] to bridge to kotlinx-io for codec layer integration.
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
     * @return number of bytes read, or -1 on EOF.
     */
    suspend fun read(buf: NativeBuf): Int

    /**
     * Writes bytes from [buf] between [NativeBuf.readerIndex] and [NativeBuf.writerIndex].
     * Advances [NativeBuf.readerIndex] by the number of bytes written.
     * Data is buffered until [flush] is called.
     *
     * @return number of bytes written to the outbound buffer.
     */
    suspend fun write(buf: NativeBuf): Int

    /**
     * Flushes all buffered outbound data to the network.
     * Enables writev/gather-write optimisation when multiple [write]
     * calls precede a single [flush].
     */
    suspend fun flush()

    // --- Half-close ---

    /**
     * Shuts down the write side of this channel, signalling that
     * no more output will be sent. The read side remains open.
     */
    fun shutdownOutput()

    // --- kotlinx-io bridge (codec layer) ---

    /** Returns a [RawSource] view for reading from this channel. */
    fun asSource(): RawSource

    /** Returns a [RawSink] view for writing to this channel (includes flush semantics). */
    fun asSink(): RawSink

    /** Closes both read and write sides and releases resources. */
    override fun close()
}
