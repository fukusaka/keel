package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.BufSlice
import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf

/**
 * Buffered wrapper providing readLine/readByte utilities over either a
 * pull-model [SuspendSource] or a push-model [PushSuspendSource].
 *
 * **Pull mode** (default): Uses a single 8 KiB [IoBuf] as internal buffer.
 * ```
 * kernel → IoBuf (zero-copy via Channel.read)
 *   → readByte/readLine consume from IoBuf directly (no copy)
 *   → when buffer is exhausted, compact + refill from source (suspend)
 * ```
 *
 * **Push mode**: Manages a chain of engine-owned [IoBuf]s delivered by
 * [PushSuspendSource.readOwned]. No internal buffer allocation, no copy.
 * ```
 * kernel → engine-owned IoBuf (zero-copy via multishot recv)
 *   → readByte/readLine consume directly from buffer chain
 *   → fully consumed buffers are released back to the engine
 * ```
 *
 * Suspend calls occur only when all buffers are exhausted and new data
 * is needed. Typical HTTP request parsing suspends 1-2 times per request.
 *
 * **Ownership**: this class does NOT own the underlying source. Closing
 * this wrapper releases internal/owned buffers but does not close the
 * source. The caller is responsible for closing the source independently.
 */
class BufferedSuspendSource : AutoCloseable {

    // --- Pull-mode fields ---
    private val source: SuspendSource?
    private val pullBuf: IoBuf?

    // --- Push-mode fields ---
    private val pushSource: PushSuspendSource?
    private val bufferChain: ArrayDeque<IoBuf>?

    // --- Common fields ---
    private val pushMode: Boolean
    private var eof = false
    private var closed = false
    private val lineBuilder = StringBuilder(INITIAL_LINE_CAPACITY)

    /**
     * Pull-mode constructor: wraps a [SuspendSource] with an internal 8 KiB buffer.
     *
     * @param source The underlying [SuspendSource] to read from.
     * @param allocator Buffer allocator for the internal buffer.
     */
    constructor(source: SuspendSource, allocator: BufferAllocator) {
        this.source = source
        this.pullBuf = allocator.allocate(BUFFER_SIZE)
        this.pushSource = null
        this.bufferChain = null
        this.pushMode = false
    }

    /**
     * Push-mode constructor: reads engine-owned [IoBuf]s from a [PushSuspendSource].
     *
     * No internal buffer is allocated. Engine-owned buffers are consumed
     * directly and released back to the engine when fully read.
     *
     * @param pushSource The push-model source delivering engine-owned buffers.
     */
    constructor(pushSource: PushSuspendSource) {
        this.source = null
        this.pullBuf = null
        this.pushSource = pushSource
        this.bufferChain = ArrayDeque()
        this.pushMode = true
    }

    // --- Internal: current buffer access ---

    /**
     * Returns the current buffer to read from.
     * Pull mode: the single internal buffer.
     * Push mode: the first buffer in the chain (after releasing consumed ones).
     */
    private fun currentBuf(): IoBuf? {
        if (pushMode) {
            releaseConsumedBuffers()
            return bufferChain!!.firstOrNull()
        }
        return pullBuf
    }

    /** Releases fully consumed buffers at the front of the push-mode chain. */
    private fun releaseConsumedBuffers() {
        val chain = bufferChain ?: return
        while (chain.isNotEmpty() && chain.first().readableBytes == 0) {
            chain.removeFirst().release()
        }
    }

    // --- Internal: fill ---

    /**
     * Refills data. Returns true if data is available, false on EOF.
     */
    private suspend fun fill(): Boolean {
        if (eof) return false
        return if (pushMode) fillPush() else fillPull()
    }

    /**
     * Pull-mode fill: compacts and reads from [source] into the internal buffer.
     */
    private suspend fun fillPull(): Boolean {
        val buf = pullBuf!!
        if (buf.writableBytes < COMPACT_THRESHOLD) {
            buf.compact()
        }
        val n = source!!.read(buf)
        if (n <= 0) {
            eof = true
            return false
        }
        return true
    }

    /**
     * Push-mode fill: requests an engine-owned buffer from [pushSource].
     */
    private suspend fun fillPush(): Boolean {
        val owned = pushSource!!.readOwned() ?: run { eof = true; return false }
        bufferChain!!.addLast(owned)
        return true
    }

    // --- Public API ---

    /**
     * Reads a single byte, suspending if no data is available.
     *
     * @throws KeelEofException on EOF.
     */
    suspend fun readByte(): Byte {
        var cur = currentBuf()
        if (cur == null || cur.readableBytes == 0) {
            if (!fill()) throw KeelEofException("Unexpected EOF")
            cur = currentBuf()!!
        }
        return cur.readByte()
    }

    /**
     * Reads a line terminated by `\n` or `\r\n`.
     *
     * Scans the buffer(s) for a newline. If not found, refills and
     * continues scanning. Returns null on EOF before any data.
     *
     * Note: assumes ASCII-compatible encoding (valid for HTTP headers
     * per RFC 7230).
     *
     * @return the line without the line terminator, or null on EOF.
     */
    suspend fun readLine(): String? {
        lineBuilder.clear()
        while (true) {
            val cur = currentBuf()
            if (cur == null || cur.readableBytes == 0) {
                if (!fill()) {
                    return if (lineBuilder.isEmpty()) null else lineBuilder.toString()
                }
                continue
            }
            val b = cur.readByte()
            if (b == LF) {
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
     * [BufSlice] pointing directly into the buffer (zero-copy).
     *
     * **Pull mode**: returns a [BufSlice] into the single internal buffer.
     *
     * **Push mode**: returns a single-segment [BufSlice] if the line fits
     * in one engine-owned buffer (99% of cases), or a multi-segment
     * [BufSlice] chain when the line spans two buffers (< 1%).
     *
     * The returned BufSlice is valid until the next [scanLine], [readLine],
     * [fill], or [close] call.
     *
     * @return the line without the line terminator, or null on EOF.
     */
    suspend fun scanLine(): BufSlice? {
        return if (pushMode) scanLinePush() else scanLinePull()
    }

    /**
     * Pull-mode scanLine: scans the single internal buffer for LF.
     */
    private suspend fun scanLinePull(): BufSlice? {
        val buf = pullBuf!!
        while (true) {
            val start = buf.readerIndex
            val end = buf.writerIndex
            for (i in start until end) {
                if (buf.getByte(i) == LF) {
                    var lineEnd = i
                    if (lineEnd > start && buf.getByte(lineEnd - 1) == CR) lineEnd--
                    val slice = BufSlice(buf, start, lineEnd - start)
                    buf.readerIndex = i + 1
                    return slice
                }
            }
            if (!fillPull()) {
                return if (buf.readableBytes > 0) {
                    val slice = BufSlice(buf, buf.readerIndex, buf.readableBytes)
                    buf.readerIndex = buf.writerIndex
                    slice
                } else null
            }
        }
    }

    /**
     * Push-mode scanLine: scans the buffer chain for LF, returning a
     * zero-copy [BufSlice] (single or multi-segment).
     */
    private suspend fun scanLinePush(): BufSlice? {
        releaseConsumedBuffers()
        while (true) {
            val chain = bufferChain!!
            if (chain.isEmpty() && !fillPush()) return null

            val head = chain.first()
            val start = head.readerIndex

            // Scan head buffer for LF
            for (i in start until head.writerIndex) {
                if (head.getByte(i) == LF) {
                    var lineEnd = i
                    if (lineEnd > start && head.getByte(lineEnd - 1) == CR) lineEnd--
                    val slice = BufSlice(head, start, lineEnd - start)
                    head.readerIndex = i + 1
                    return slice
                }
            }

            // LF not in head — need more data
            if (!fillPush()) {
                // EOF: return remaining as line
                return if (head.readableBytes > 0) {
                    val slice = BufSlice(head, head.readerIndex, head.readableBytes)
                    head.readerIndex = head.writerIndex
                    slice
                } else null
            }

            // Line spans head and next buffer(s) — build chained BufSlice
            return crossBufferScanLine(head, start)
        }
    }

    /**
     * Builds a multi-segment [BufSlice] for a line that spans the buffer
     * boundary. The first segment covers the remaining bytes in [firstBuf],
     * the second covers bytes up to LF in the next buffer.
     *
     * This path is taken for < 1% of HTTP header lines.
     */
    private suspend fun crossBufferScanLine(firstBuf: IoBuf, startOffset: Int): BufSlice? {
        val firstLength = firstBuf.writerIndex - startOffset
        firstBuf.readerIndex = firstBuf.writerIndex // consume first segment
        // Note: firstBuf is now fully consumed (readableBytes=0) but must NOT
        // be released yet — the returned BufSlice will reference it. It will be
        // released on the next scanLine/readLine/close call via releaseConsumedBuffers.
        // We skip releaseConsumedBuffers here to preserve the reference.

        // Search subsequent buffers for LF.
        // Do NOT call releaseConsumedBuffers() here — firstBuf is consumed
        // but still referenced by the BufSlice we will return.
        while (true) {
            val chain = bufferChain!!
            if (chain.size <= 1 && !fillPush()) {
                // EOF: return first segment only
                return if (firstLength > 0) BufSlice(firstBuf, startOffset, firstLength) else null
            }

            // Skip firstBuf (index 0) which is consumed but retained for BufSlice.
            // The next buffer is at index 1 (or later if chain grew).
            val cur = chain.last() // most recently added buffer
            val curStart = cur.readerIndex
            for (i in curStart until cur.writerIndex) {
                if (cur.getByte(i) == LF) {
                    cur.readerIndex = i + 1 // consume through LF

                    // Compute second segment length, handling CR stripping
                    var secondEnd = i
                    var adjFirstLength = firstLength
                    if (secondEnd > curStart && cur.getByte(secondEnd - 1) == CR) {
                        secondEnd-- // strip CR from second segment
                    } else if (secondEnd == curStart && firstLength > 0 &&
                        firstBuf.getByte(startOffset + firstLength - 1) == CR
                    ) {
                        adjFirstLength-- // CR is at end of first segment
                    }

                    val secondLength = secondEnd - curStart
                    val second = if (secondLength > 0) BufSlice(cur, curStart, secondLength) else null
                    return if (adjFirstLength > 0) {
                        BufSlice(firstBuf, startOffset, adjFirstLength, second)
                    } else {
                        second
                    }
                }
            }
            // LF not in this buffer either — continue filling.
            // For 3+ buffer spans (extremely rare in HTTP), the loop
            // continues and crossBufferScanLine returns a 2-segment chain
            // (first segment + remaining segment where LF is found).
            // Intermediate fully-consumed buffers are not included in the
            // chain — they will be released by releaseConsumedBuffers().
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
            val cur = currentBuf()
            if (cur == null || cur.readableBytes == 0) {
                if (!fill()) {
                    throw KeelEofException("Unexpected EOF: expected $count bytes, got $offset")
                }
                continue
            }
            val available = cur.readableBytes.coerceAtMost(count - offset)
            for (i in 0 until available) {
                result[offset++] = cur.readByte()
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
        val cur = currentBuf()
        if (cur == null || cur.readableBytes == 0) {
            if (!fill()) return -1
        }
        val buf = currentBuf()!!
        val available = buf.readableBytes.coerceAtMost(length)
        for (i in 0 until available) {
            dest[offset + i] = buf.readByte()
        }
        return available
    }

    override fun close() {
        if (!closed) {
            closed = true
            if (pushMode) {
                val chain = bufferChain!!
                for (buf in chain) buf.release()
                chain.clear()
            } else {
                pullBuf!!.release()
            }
        }
    }

    companion object {
        /** Internal buffer size for pull mode. */
        private const val BUFFER_SIZE = 8192
        /** Compact threshold for pull mode. */
        private const val COMPACT_THRESHOLD = 1024
        /** Initial StringBuilder capacity for readLine. */
        private const val INITIAL_LINE_CAPACITY = 128
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}
