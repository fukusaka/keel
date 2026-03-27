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
        lineBuilder.clear()
        while (true) {
            if (buf.readableBytes == 0) {
                if (!fill()) {
                    return if (lineBuilder.isEmpty()) null else lineBuilder.toString()
                }
            }
            val b = buf.readByte()
            if (b == LF) {
                // Remove trailing \r if present
                if (lineBuilder.isNotEmpty() && lineBuilder[lineBuilder.length - 1] == '\r') {
                    lineBuilder.deleteAt(lineBuilder.length - 1)
                }
                return lineBuilder.toString()
            }
            lineBuilder.append(b.toInt().toChar())
        }
    }

    /**
     * Scans for a line terminated by `\n` or `\r\n` and returns it as a
     * [BufSlice] pointing directly into the internal buffer (zero-copy).
     *
     * The returned BufSlice is valid until the next [scanLine], [readLine],
     * [fill], or [close] call. The caller must not hold a reference beyond
     * the current parse step.
     *
     * If the line does not fit in the buffer (longer than [BUFFER_SIZE]),
     * the buffer is compacted and refilled. HTTP header lines are typically
     * a few hundred bytes, so this path is rarely taken.
     *
     * @return the line without the line terminator, or null on EOF.
     */
    suspend fun scanLine(): BufSlice? {
        while (true) {
            // Scan readable region for LF
            val start = buf.readerIndex
            val end = buf.writerIndex
            for (i in start until end) {
                if (buf.getByte(i) == LF) {
                    // Found LF — compute line length excluding terminators
                    var lineEnd = i
                    if (lineEnd > start && buf.getByte(lineEnd - 1) == CR) {
                        lineEnd-- // strip \r
                    }
                    val slice = BufSlice(buf, start, lineEnd - start)
                    buf.readerIndex = i + 1 // consume through LF
                    return slice
                }
            }

            // LF not found in current buffer — need more data
            if (!fill()) {
                // EOF — return remaining bytes as a line, or null if empty
                return if (buf.readableBytes > 0) {
                    val slice = BufSlice(buf, buf.readerIndex, buf.readableBytes)
                    buf.readerIndex = buf.writerIndex
                    slice
                } else {
                    null
                }
            }
            // After fill(), compact() moved data to offset 0 and appended new data.
            // Loop back to scan from the new readerIndex.
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

    /** Reused across readLine calls to avoid per-call StringBuilder allocation. */
    private val lineBuilder = StringBuilder(INITIAL_LINE_CAPACITY)

    companion object {
        /**
         * Internal buffer size. 8 KiB matches the default kotlinx-io segment
         * size and balances suspend frequency against memory usage for typical
         * HTTP request header sizes.
         */
        private const val BUFFER_SIZE = 8192
        /** Initial StringBuilder capacity for readLine. Covers typical HTTP header lines. */
        private const val INITIAL_LINE_CAPACITY = 128
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}
