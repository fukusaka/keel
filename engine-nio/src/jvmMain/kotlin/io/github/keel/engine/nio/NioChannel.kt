package io.github.keel.engine.nio

import io.github.keel.core.BufferAllocator
import io.github.keel.core.Channel
import io.github.keel.core.NativeBuf
import io.github.keel.core.SocketAddress
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel

/**
 * Snapshot of a buffered write: the [NativeBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 *
 * We record offset/length separately because [NativeBuf.readerIndex] is
 * advanced at write() time so the caller can reuse the buffer immediately.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * Java NIO [SocketChannel]-based [Channel] implementation for JVM.
 *
 * **Zero-copy I/O**: read/write pass [NativeBuf.unsafeBuffer] (DirectByteBuffer)
 * directly to [SocketChannel.read]/[SocketChannel.write] — no intermediate
 * ByteArray copy. The ByteBuffer's position/limit are set to match
 * [NativeBuf.writerIndex]/[NativeBuf.readerIndex] before each syscall.
 *
 * **Write/flush separation**: [write] retains the [NativeBuf] and records
 * the byte range to send. [flush] iterates all pending writes and calls
 * [SocketChannel.write] for each, then releases the buffers.
 *
 * Phase (a): blocking mode. [SocketChannel] is in blocking mode so
 * read/write calls block until data is available or sent. No Selector
 * needed; Phase (b) will switch to non-blocking + Selector.
 *
 * ```
 * Read path (zero-copy):
 *   SocketChannel.read(ByteBuffer) --> NativeBuf.unsafeBuffer
 *     (data lands directly in DirectByteBuffer's native memory)
 *
 * Write path (buffered, zero-copy flush):
 *   write(buf)  --> retain buf, record offset/length in PendingWrite
 *   flush()     --> SocketChannel.write(ByteBuffer) for each PendingWrite
 *                   release each buf
 *
 * Codec bridge (byte-by-byte copy, acceptable for codec layer):
 *   asSource() --> ChannelSource --> NativeBuf --> kotlinx-io Buffer
 *   asSink()   --> ChannelSink   --> kotlinx-io Buffer --> NativeBuf
 * ```
 *
 * @param socketChannel The connected SocketChannel for this channel.
 * @param allocator     Buffer allocator for [asSource]/[asSink] bridge.
 */
internal class NioChannel(
    private val socketChannel: SocketChannel,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
) : Channel {

    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    /** Phase (a): no-op. Will suspend until closed in Phase (b). */
    override suspend fun awaitClosed() {}

    override suspend fun read(buf: NativeBuf): Int = readBlocking(buf)

    override suspend fun write(buf: NativeBuf): Int = writeBlocking(buf)

    override suspend fun flush() = flushBlocking()

    /**
     * Reads bytes into [buf] via zero-copy SocketChannel read.
     *
     * Sets the ByteBuffer's position to [NativeBuf.writerIndex] and limit
     * to [NativeBuf.capacity] so data lands at the correct offset.
     * Phase (a): blocking mode, so this call blocks until data arrives.
     *
     * @return number of bytes read, or -1 on EOF.
     */
    internal fun readBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        val bb = buf.unsafeBuffer
        bb.position(buf.writerIndex)
        bb.limit(buf.capacity)

        val n = socketChannel.read(bb)
        if (n > 0) {
            buf.writerIndex += n
            return n
        }
        if (n < 0) return -1 // EOF
        return 0
    }

    /**
     * Buffers a write by retaining [buf] and recording the current readable range.
     *
     * The caller's [NativeBuf.readerIndex] is advanced immediately so the
     * buffer can be reused or released by the caller. The actual SocketChannel
     * write happens on [flushBlocking].
     *
     * @return number of bytes buffered.
     */
    internal fun writeBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val bytes = buf.readableBytes
        if (bytes == 0) return 0
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        return bytes
    }

    /**
     * Sends all buffered writes to the network via SocketChannel.
     *
     * Each [PendingWrite] is written using zero-copy ByteBuffer access.
     * The ByteBuffer's position/limit are set to the recorded offset/length
     * before each [SocketChannel.write] call.
     */
    internal fun flushBlocking() {
        check(_open) { "Channel is closed" }
        for (pw in pendingWrites) {
            val bb = pw.buf.unsafeBuffer
            bb.position(pw.offset)
            bb.limit(pw.offset + pw.length)
            socketChannel.write(bb)
            pw.buf.release()
        }
        pendingWrites.clear()
    }

    /**
     * Sends TCP FIN to the peer via [SocketChannel.shutdownOutput].
     * The read side remains open so the peer's remaining data can be consumed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            socketChannel.shutdownOutput()
        }
    }

    override fun asSource(): RawSource = ChannelSource(this, allocator)

    override fun asSink(): RawSink = ChannelSink(this, allocator)

    /**
     * Closes the SocketChannel and releases all pending writes.
     * Unflushed data is discarded (buffers are released without sending).
     */
    override fun close() {
        if (_open) {
            _open = false
            _active = false
            for (pw in pendingWrites) {
                pw.buf.release()
            }
            pendingWrites.clear()
            socketChannel.close()
        }
    }

    companion object {
        /** Extracts [SocketAddress] from a Java NIO [InetSocketAddress]. */
        internal fun toSocketAddress(addr: java.net.SocketAddress?): SocketAddress? {
            val inet = addr as? InetSocketAddress ?: return null
            return SocketAddress(inet.address.hostAddress, inet.port)
        }
    }
}
