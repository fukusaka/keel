package io.github.fukusaka.keel.io

/**
 * Buffered wrapper over [SuspendSink] providing writeString/writeByte utilities.
 *
 * Uses a [NativeBuf] as the internal buffer for zero-copy I/O:
 * ```
 * writeString/writeByte → NativeBuf (buffer accumulation)
 *   → when buffer is full or flush() is called:
 *     NativeBuf → Channel.write (zero-copy) → Channel.flush → kernel
 * ```
 *
 * @param sink The underlying [SuspendSink] to write to.
 * @param allocator Buffer allocator for the internal buffer.
 */
class BufferedSuspendSink(
    private val sink: SuspendSink,
    private val allocator: BufferAllocator,
) : AutoCloseable {

    private val buf = allocator.allocate(BUFFER_SIZE)

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
            for (i in 0 until chunk) {
                buf.writeByte(bytes[pos++])
            }
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
     * Sends the internal buffer's contents to the underlying sink
     * and waits for delivery before clearing the buffer.
     *
     * Must call sink.flush() before buf.clear() because Channel.write()
     * retains a reference to buf and records offset/length. If buf were
     * cleared and reused before flush, new data would overwrite the
     * retained buffer's memory, corrupting pending writes.
     */
    private suspend fun flushBuffer() {
        if (buf.readableBytes > 0) {
            sink.write(buf)
            sink.flush()
            buf.clear()
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            buf.release()
        }
    }

    private var closed = false

    companion object {
        /**
         * Internal buffer size. 8 KiB matches the default kotlinx-io segment
         * size and balances syscall frequency against memory usage for typical
         * HTTP response sizes.
         */
        private const val BUFFER_SIZE = 8192
    }
}
