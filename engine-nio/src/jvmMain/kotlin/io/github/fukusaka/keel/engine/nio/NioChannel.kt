package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.io.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.io.NativeBuf
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
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
 * **SelectionKey caching**: The [selectionKey] is registered once at channel
 * creation with `interestOps=0`. Each `read()` call toggles interest ops
 * via [NioEventLoop.setInterest] instead of re-registering with the Selector.
 * This eliminates per-read JNI overhead (Netty uses the same pattern).
 *
 * **Write/flush separation**: [write] retains the [NativeBuf] and records
 * the byte range to send. [flush] iterates all pending writes and calls
 * [SocketChannel.write] for each (gather-write for multiple buffers).
 *
 * ```
 * Read path (zero-copy, async via EventLoop):
 *   SocketChannel.read(ByteBuffer) → NativeBuf.unsafeBuffer
 *   If n == 0: setInterest(key, OP_READ, cont) → select() → resume → retry
 *
 * Write path (buffered, zero-copy flush):
 *   write(buf)  → retain buf, record offset/length in PendingWrite
 *   flush()     → SocketChannel.write(ByteBuffer[]) gather-write
 * ```
 *
 * **Backpressure**: [pendingWrites] has no upper bound. A producer that
 * calls [write] without [flush] can accumulate unbounded memory. This is
 * acceptable for the current HTTP server use case where the ktor-engine
 * layer calls flush() after each response. An application-level write
 * watermark (similar to Netty's ChannelOutboundBuffer) is deferred to
 * Phase 7.
 *
 * **Thread model**: all public methods must be called from the EventLoop
 * thread (via [coroutineDispatcher]). State fields ([_open],
 * [pendingWrites]) are not thread-safe.
 *
 * @param socketChannel The connected SocketChannel (non-blocking).
 * @param selectionKey  Cached SelectionKey registered with the worker Selector.
 * @param eventLoop     The [NioEventLoop] for readiness notification.
 * @param allocator     Buffer allocator for read operations.
 */
internal class NioChannel(
    private val socketChannel: SocketChannel,
    private val selectionKey: SelectionKey,
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

    /** Returns the worker EventLoop's dispatcher for same-thread I/O execution. */
    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop
    override val supportsDeferredFlush: Boolean get() = true

    /** ForkJoinPool work-stealing outperforms EventLoop fixed-partition for pipeline. */
    @Suppress("InjectDispatcher") // Intentional: NIO pipeline runs on Dispatchers.Default (design.md §17)
    override val appDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    /** No-op. JVM SocketChannel has no close-completion callback. */
    override suspend fun awaitClosed() {}

    /**
     * Reads bytes into [buf] via zero-copy SocketChannel read.
     *
     * On non-blocking mode, [SocketChannel.read] returns 0 if no data
     * is available. In that case, sets [SelectionKey.OP_READ] interest
     * on the cached [selectionKey] and suspends. No Selector re-registration
     * needed (interestOps toggle only).
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
                eventLoop.setInterest(selectionKey, SelectionKey.OP_READ, cont)
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
     * Handles partial writes and send buffer full (write returns 0 on
     * non-blocking SocketChannel): suspends on [SelectionKey.OP_WRITE]
     * until the socket becomes writable, then retries.
     *
     * Uses [GatheringByteChannel.write] for gather-write when multiple
     * writes are buffered. Falls back to single write() for one buffer.
     */
    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        if (pendingWrites.isEmpty()) return

        if (pendingWrites.size == 1) {
            flushSingle(pendingWrites[0])
        } else {
            flushGather()
        }
        pendingWrites.clear()
    }

    /**
     * Writes a single [PendingWrite] with partial write and OP_WRITE handling.
     *
     * Loops until all bytes are written. When [SocketChannel.write] returns 0
     * (send buffer full), suspends on [SelectionKey.OP_WRITE] until the socket
     * becomes writable, then retries.
     */
    private suspend fun flushSingle(pw: PendingWrite) {
        val bb = pw.buf.unsafeBuffer
        bb.position(pw.offset)
        bb.limit(pw.offset + pw.length)
        while (bb.hasRemaining()) {
            val n = socketChannel.write(bb)
            if (n == 0) {
                // Send buffer full — suspend until writable
                suspendCancellableCoroutine<Unit> { cont ->
                    eventLoop.setInterest(selectionKey, SelectionKey.OP_WRITE, cont)
                    cont.invokeOnCancellation {
                        eventLoop.removeInterest(selectionKey, SelectionKey.OP_WRITE)
                    }
                }
            }
        }
        pw.buf.release()
    }

    /**
     * Writes multiple pending buffers using gather write.
     *
     * Attempts a single [GatheringByteChannel.write] for all buffers.
     * On partial write, completed buffers are released and the remaining
     * data falls through to [flushSingle] for per-buffer retry with
     * OP_WRITE suspension.
     */
    private suspend fun flushGather() {
        val bbArray = Array(pendingWrites.size) { i ->
            val pw = pendingWrites[i]
            pw.buf.unsafeBuffer.duplicate().apply {
                position(pw.offset)
                limit(pw.offset + pw.length)
            }
        }
        val totalBytes = bbArray.sumOf { it.remaining().toLong() }
        val written = socketChannel.write(bbArray)

        if (written >= totalBytes) {
            // All data written
            for (pw in pendingWrites) pw.buf.release()
            return
        }

        // Partial write or send buffer full: walk buffers to find split point.
        // Fully written buffers have no remaining bytes in their ByteBuffer.
        for (i in pendingWrites.indices) {
            val pw = pendingWrites[i]
            val bb = bbArray[i]
            if (!bb.hasRemaining()) {
                // Fully written
                pw.buf.release()
            } else {
                // Partially written or not written at all — flush remaining
                // via flushSingle which handles OP_WRITE suspension.
                // Note: pw.buf's retain (from Channel.write) is consumed by
                // flushSingle's release — the original PendingWrite is not
                // released here since flushSingle takes ownership.
                val consumed = bb.position() - pw.offset
                flushSingle(PendingWrite(pw.buf, pw.offset + consumed, pw.length - consumed))
                // Flush remaining buffers individually with OP_WRITE handling
                for (j in (i + 1) until pendingWrites.size) {
                    flushSingle(pendingWrites[j])
                }
                return
            }
        }
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

    /**
     * Closes the SocketChannel, cancels the cached SelectionKey,
     * and releases all pending writes.
     */
    override fun close() {
        if (_open) {
            _open = false
            _active = false
            selectionKey.cancel()
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
