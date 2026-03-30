package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf

/**
 * Buffered wrapper over [SuspendSink] providing writeString/writeByte utilities.
 *
 * Uses [IoBuf] instances from [allocator] for zero-copy I/O.
 * The flush strategy is controlled by [deferFlush]:
 *
 * **deferFlush = true** (EventLoop-based engines: kqueue/epoll/NIO):
 * ```
 * writeString/writeByte → IoBuf (buffer accumulation)
 *   → when buffer is full:
 *     IoBuf → Channel.write (enqueue) → allocate fresh IoBuf
 *   → when flush() is called:
 *     Channel.flush → writev (single syscall for all queued buffers)
 * ```
 *
 * **deferFlush = false** (push-model engines: Netty/NWConnection/Node.js):
 * ```
 * writeString/writeByte → IoBuf (buffer accumulation)
 *   → when buffer is full:
 *     IoBuf → Channel.write + Channel.flush (immediate OS write) → clear
 * ```
 *
 * **Ownership**: this class does NOT own [sink]. Closing this wrapper
 * releases the internal buffer but does not close or flush the underlying
 * sink. The caller must call [flush] before [close] to ensure all buffered
 * data is written, and must close [sink] independently.
 *
 * **Thread safety**: not thread-safe. Designed for single-threaded use
 * within an EventLoop or a single coroutine scope.
 *
 * @param sink The underlying [SuspendSink] to write to.
 * @param allocator Buffer allocator for the internal buffer.
 * @param deferFlush When true, [flushBuffer] enqueues buffers without OS write,
 *                   deferring the actual I/O to the caller's [flush]. When false
 *                   (default), each buffer fill triggers an immediate OS write.
 *                   Set to true for EventLoop-based engines (kqueue/epoll/NIO)
 *                   where write and flush run on the same single thread.
 */
class BufferedSuspendSink(
    private val sink: SuspendSink,
    private val allocator: BufferAllocator,
    private val deferFlush: Boolean = false,
) : AutoCloseable {

    private var buf = allocator.allocate(BUFFER_SIZE)
    private var closed = false

    /**
     * Writes a single byte, flushing the buffer if full.
     */
    suspend fun writeByte(b: Byte) {
        if (buf.writableBytes == 0) flushBuffer()
        buf.writeByte(b)
    }

    /**
     * Writes a UTF-8 encoded string.
     *
     * Large strings are written in chunks matching the buffer capacity.
     */
    suspend fun writeString(text: String) {
        val bytes = text.encodeToByteArray()
        write(bytes, 0, bytes.size)
    }

    /**
     * Writes an ASCII string directly into the buffer without intermediate
     * ByteArray allocation. Each character is truncated to its low 8 bits,
     * which is correct for HTTP headers, status lines, and other US-ASCII
     * protocol text.
     *
     * Prefer this over [writeString] on the HTTP write path to avoid
     * per-call `String.encodeToByteArray()` allocations.
     */
    suspend fun writeAscii(text: String) {
        var pos = 0
        var remaining = text.length
        while (remaining > 0) {
            if (buf.writableBytes == 0) flushBuffer()
            val chunk = remaining.coerceAtMost(buf.writableBytes)
            buf.writeAsciiString(text, pos, chunk)
            pos += chunk
            remaining -= chunk
        }
    }

    /**
     * Writes all bytes from [bytes].
     */
    suspend fun write(bytes: ByteArray) {
        write(bytes, 0, bytes.size)
    }

    /**
     * Writes [length] bytes from [bytes] starting at [offset].
     */
    suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        var pos = offset
        var remaining = length
        while (remaining > 0) {
            if (buf.writableBytes == 0) flushBuffer()
            val chunk = remaining.coerceAtMost(buf.writableBytes)
            buf.writeBytes(bytes, pos, chunk)
            pos += chunk
            remaining -= chunk
        }
    }

    /**
     * Flushes the internal buffer and the underlying sink.
     */
    suspend fun flush() {
        flushBuffer()
        sink.flush()
    }

    /**
     * Sends the internal buffer's contents to the underlying sink.
     *
     * When [deferFlush] is true (EventLoop-based engines: kqueue/epoll/NIO):
     * enqueues the buffer via sink.write(), releases our reference, and
     * allocates a fresh buffer from the pool. The old buffer remains in the
     * Channel's pending-write queue until the caller's [flush] sends all
     * accumulated buffers in a single writev() syscall.
     *
     * When [deferFlush] is false (push-model engines: Netty/NWConnection/Node.js):
     * calls sink.write() + sink.flush() synchronously and reuses the same
     * buffer. This is required because push-model engines may flush on a
     * different thread, making pool-based buffer recycling unsafe.
     */
    private suspend fun flushBuffer() {
        if (buf.readableBytes > 0) {
            if (deferFlush) {
                // Deferred flush: Channel.write() retains buf internally.
                // We release our reference and allocate a fresh buffer.
                // Allocate BEFORE release to ensure buf field always points
                // to a valid buffer — if allocate throws, buf is still valid
                // and close() can release it safely.
                sink.write(buf)
                val oldBuf = buf
                buf = allocator.allocate(BUFFER_SIZE)
                oldBuf.release()
            } else {
                // Immediate flush: write + flush, then reuse the same buffer.
                sink.write(buf)
                sink.flush()
                buf.clear()
            }
        }
    }

    /**
     * Releases the internal buffer. Does NOT flush buffered data or
     * close the underlying [sink].
     *
     * Call [flush] before [close] to ensure all buffered data is sent.
     * Any data remaining in the internal buffer at close time is discarded.
     *
     * Safe to call multiple times (idempotent via `closed` flag).
     */
    override fun close() {
        if (!closed) {
            closed = true
            buf.release()
        }
    }

    companion object {
        /**
         * Internal buffer size. 8 KiB matches the default kotlinx-io segment
         * size and balances syscall frequency against memory usage for typical
         * HTTP response sizes.
         */
        private const val BUFFER_SIZE = 8192
    }
}
