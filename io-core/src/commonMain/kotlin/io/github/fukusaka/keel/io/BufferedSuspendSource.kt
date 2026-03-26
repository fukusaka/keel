package io.github.fukusaka.keel.io

/**
 * Buffered wrapper over [SuspendSource] providing readLine/readByte utilities.
 *
 * Uses a [NativeBuf] as the internal buffer for zero-copy I/O:
 * ```
 * kernel → NativeBuf (zero-copy via Channel.read)
 *   → readByte/readLine consume from NativeBuf directly (no copy)
 *   → when buffer is exhausted, compact + refill from source (suspend)
 * ```
 *
 * Suspend calls occur only when the internal buffer is empty and needs
 * refilling. Typical HTTP request parsing suspends 1-2 times per request
 * (header + body boundary).
 *
 * **Ownership**: this class does NOT own [source]. Closing this wrapper
 * releases the internal buffer but does not close the underlying source.
 * The caller is responsible for closing [source] independently.
 *
 * @param source The underlying [SuspendSource] to read from.
 * @param allocator Buffer allocator for the internal buffer.
 */
class BufferedSuspendSource(
    private val source: SuspendSource,
    private val allocator: BufferAllocator,
) : AutoCloseable {

    private val buf = allocator.allocate(BUFFER_SIZE)
    private var eof = false

    /**
     * Refills the internal buffer from the underlying source.
     * Compacts the buffer first to maximize writable space.
     *
     * @return true if data was read, false on EOF.
     */
    private suspend fun fill(): Boolean {
        if (eof) return false
        buf.compact()
        val n = source.read(buf)
        if (n <= 0) {
            eof = true
            return false
        }
        return true
    }

    /**
     * Reads a single byte, suspending if the buffer is empty.
     *
     * @throws KeelEofException on EOF.
     */
    suspend fun readByte(): Byte {
        if (buf.readableBytes == 0 && !fill()) {
            throw KeelEofException("Unexpected EOF")
        }
        return buf.readByte()
    }

    /**
     * Reads a line terminated by `\n` or `\r\n`.
     *
     * Scans the internal buffer for a newline. If not found, refills
     * and continues scanning. Returns null on EOF before any data.
     *
     * Note: assumes ASCII-compatible encoding (valid for HTTP headers per
     * RFC 7230). Multi-byte UTF-8 sequences are not handled correctly.
     *
     * @return the line without the line terminator, or null on EOF.
     */
    suspend fun readLine(): String? {
        val sb = StringBuilder()
        while (true) {
            if (buf.readableBytes == 0) {
                if (!fill()) {
                    return if (sb.isEmpty()) null else sb.toString()
                }
            }
            val b = buf.readByte()
            if (b == LF) {
                // Remove trailing \r if present
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') {
                    sb.deleteAt(sb.length - 1)
                }
                return sb.toString()
            }
            sb.append(b.toInt().toChar())
        }
    }

    /**
     * Reads exactly [count] bytes into a new ByteArray.
     *
     * @throws KeelEofException if EOF is reached before [count] bytes.
     */
    suspend fun readByteArray(count: Int): ByteArray {
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            if (buf.readableBytes == 0 && !fill()) {
                throw KeelEofException("Unexpected EOF: expected $count bytes, got $offset")
            }
            val available = buf.readableBytes.coerceAtMost(count - offset)
            for (i in 0 until available) {
                result[offset++] = buf.readByte()
            }
        }
        return result
    }

    /**
     * Reads up to [length] bytes into [dest] starting at [offset].
     *
     * Used for request body bridging in the Ktor adapter.
     *
     * @return number of bytes read, or -1 on EOF.
     */
    suspend fun readAtMostTo(dest: ByteArray, offset: Int, length: Int): Int {
        if (buf.readableBytes == 0 && !fill()) return -1
        val available = buf.readableBytes.coerceAtMost(length)
        for (i in 0 until available) {
            dest[offset + i] = buf.readByte()
        }
        return available
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
         * size and balances suspend frequency against memory usage for typical
         * HTTP request header sizes.
         */
        private const val BUFFER_SIZE = 8192
        private const val LF = '\n'.code.toByte()
    }
}
