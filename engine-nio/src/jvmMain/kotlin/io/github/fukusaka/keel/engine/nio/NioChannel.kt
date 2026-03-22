package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.NativeBuf
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
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
 * ByteArray copy.
 *
 * **Write/flush separation**: [write] retains the [NativeBuf] and records
 * the byte range to send. [flush] iterates all pending writes and calls
 * [SocketChannel.write] for each (gather-write for multiple buffers).
 *
 * **Async read via EventLoop**: The [SocketChannel] is in non-blocking mode.
 * When `read()` returns 0 (no data), the channel is registered with the
 * [NioEventLoop] for [SelectionKey.OP_READ] and the coroutine suspends.
 * The EventLoop's [java.nio.channels.Selector] resumes the coroutine
 * when the channel becomes readable.
 *
 * ```
 * Read path (zero-copy, async via EventLoop):
 *   SocketChannel.read(ByteBuffer) → NativeBuf.unsafeBuffer
 *   If n == 0: suspendCancellableCoroutine + eventLoop.register(ch, OP_READ)
 *   EventLoop select() fires → continuation.resume(Unit) → retry read
 *
 * Write path (buffered, zero-copy flush):
 *   write(buf)  → retain buf, record offset/length in PendingWrite
 *   flush()     → SocketChannel.write(ByteBuffer[]) gather-write
 * ```
 *
 * @param socketChannel The connected SocketChannel (non-blocking).
 * @param eventLoop     The [NioEventLoop] for readiness notification.
 * @param allocator     Buffer allocator for [asSource]/[asSink] bridge.
 */
internal class NioChannel(
    private val socketChannel: SocketChannel,
    private val eventLoop: NioEventLoop,
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

    /** No-op. JVM SocketChannel has no close-completion callback. */
    override suspend fun awaitClosed() {}

    /**
     * Reads bytes into [buf] via zero-copy SocketChannel read.
     *
     * On non-blocking mode, [SocketChannel.read] returns 0 if no data
     * is available. In that case, registers with the [NioEventLoop]
     * for [SelectionKey.OP_READ] and suspends.
     *
     * @return number of bytes read, or -1 on EOF.
     */
    override suspend fun read(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        while (true) {
            val bb = buf.unsafeBuffer
            bb.position(buf.writerIndex)
            bb.limit(buf.capacity)

            val n = socketChannel.read(bb)
            if (n > 0) {
                buf.writerIndex += n
                return n
            }
            if (n < 0) return -1 // EOF

            // n == 0: no data available, suspend until readable
            suspendCancellableCoroutine<Unit> { cont ->
                eventLoop.register(socketChannel, SelectionKey.OP_READ, cont)
            }
        }
    }

    /**
     * Buffers a write by retaining [buf] and recording the current readable range.
     *
     * @return number of bytes buffered.
     */
    override suspend fun write(buf: NativeBuf): Int {
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
     * Uses [GatheringByteChannel.write] for gather-write when multiple
     * writes are buffered. Falls back to single write() for one buffer.
     */
    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        if (pendingWrites.isEmpty()) return

        if (pendingWrites.size == 1) {
            val pw = pendingWrites[0]
            val bb = pw.buf.unsafeBuffer
            bb.position(pw.offset)
            bb.limit(pw.offset + pw.length)
            socketChannel.write(bb)
            pw.buf.release()
        } else {
            val bbArray = Array(pendingWrites.size) { i ->
                val pw = pendingWrites[i]
                pw.buf.unsafeBuffer.duplicate().apply {
                    position(pw.offset)
                    limit(pw.offset + pw.length)
                }
            }
            socketChannel.write(bbArray)
            for (pw in pendingWrites) pw.buf.release()
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

    @Suppress("DEPRECATION")
    override fun asSource(): RawSource = ChannelSource(this, allocator)

    @Suppress("DEPRECATION")
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
