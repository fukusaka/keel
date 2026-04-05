package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.BufSlice
import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf

/**
 * Buffered wrapper providing readLine/readByte utilities over either a
 * pull-model [SuspendSource] or a push-model [OwnedSuspendSource].
 *
 * **Pull mode** (default): Uses a single 8 KiB [IoBuf] as internal buffer.
 * ```
 * kernel → IoBuf (zero-copy via Channel.read)
 *   → readByte/readLine consume from IoBuf directly (no copy)
 *   → when buffer is exhausted, compact + refill from source (suspend)
 * ```
 *
 * **Push mode**: Manages a chain of engine-owned [IoBuf]s delivered by
 * [OwnedSuspendSource.readOwned]. No internal buffer allocation, no copy.
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
 *
 * **Thread safety**: not thread-safe. Designed for single-threaded use
 * within an EventLoop or a single coroutine scope.
 */
class BufferedSuspendSource : AutoCloseable {

    /**
     * Internal mode discriminator. Eliminates nullable fields and `!!`
     * assertions by providing typed access to mode-specific state.
     */
    private sealed class Mode {
        class Pull(val source: SuspendSource, val buf: IoBuf) : Mode()
        class Push(val pushSource: OwnedSuspendSource, val bufferChain: ArrayDeque<IoBuf>) : Mode() {
            /** Cached head of bufferChain to avoid ArrayDeque lookup on every readByte. */
            var cachedHead: IoBuf? = null
        }
    }

    private val mode: Mode
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
        this.mode = Mode.Pull(source, allocator.allocate(BUFFER_SIZE))
    }

    /**
     * Push-mode constructor: reads engine-owned [IoBuf]s from a [OwnedSuspendSource].
     *
     * No internal buffer is allocated. Engine-owned buffers are consumed
     * directly and released back to the engine when fully read.
     *
     * @param pushSource The push-model source delivering engine-owned buffers.
     */
    constructor(pushSource: OwnedSuspendSource) {
        this.mode = Mode.Push(pushSource, ArrayDeque())
    }

    // --- Internal: current buffer access ---

    /**
     * Returns the current buffer to read from, or null if no data is available.
     * Pull mode: the single internal buffer (may have 0 readable bytes).
     * Push mode: the first buffer in the chain after releasing consumed ones.
     */
    private fun currentBuf(): IoBuf? {
        return when (val m = mode) {
            is Mode.Pull -> m.buf.takeIf { it.readableBytes > 0 }
            is Mode.Push -> {
                // Fast path: cached head still has data — skip ArrayDeque lookup.
                val cached = m.cachedHead
                if (cached != null && cached.readableBytes > 0) return cached
                // Slow path: advance to next buffer in chain.
                releaseConsumedBuffers(m)
                val head = m.bufferChain.firstOrNull()
                m.cachedHead = head
                head
            }
        }
    }

    /** Releases fully consumed buffers at the front of the push-mode chain. */
    private fun releaseConsumedBuffers(m: Mode.Push) {
        val chain = m.bufferChain
        while (chain.isNotEmpty() && chain.first().readableBytes == 0) {
            val released = chain.removeFirst()
            if (m.cachedHead === released) m.cachedHead = null
            released.release()
        }
    }

    // --- Internal: fill ---

    /**
     * Refills data and returns the buffer containing it, or null on EOF.
     * Combines fill + currentBuf into one call to eliminate the `!!` pattern
     * of `if (!fill()) ...; currentBuf()!!`.
     */
    private suspend fun fillAndGet(): IoBuf? {
        if (eof) return null
        return when (val m = mode) {
            is Mode.Pull -> fillPull(m)
            is Mode.Push -> fillPush(m)
        }
    }

    /** Pull-mode fill: compacts and reads from source into the internal buffer. */
    private suspend fun fillPull(m: Mode.Pull): IoBuf? {
        val buf = m.buf
        if (buf.writableBytes < COMPACT_THRESHOLD) {
            buf.compact()
        }
        val n = m.source.read(buf)
        if (n <= 0) {
            eof = true
            return null
        }
        return buf
    }

    /** Push-mode fill: requests an engine-owned buffer from pushSource. */
    private suspend fun fillPush(m: Mode.Push): IoBuf? {
        val owned = m.pushSource.readOwned() ?: run { eof = true; return null }
        m.bufferChain.addLast(owned)
        return owned
    }

    // --- Public API ---

    /**
     * Reads a single byte, suspending if no data is available.
     *
     * @throws KeelEofException on EOF.
     * @throws IllegalStateException if this source has been [close]d.
     */
    suspend fun readByte(): Byte {
        check(!closed) { "BufferedSuspendSource is closed" }
        val cur = currentBuf() ?: fillAndGet() ?: throw KeelEofException("Unexpected EOF")
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
     * @throws IllegalStateException if this source has been [close]d.
     */
    suspend fun readLine(): String? {
        check(!closed) { "BufferedSuspendSource is closed" }
        lineBuilder.clear()
        while (true) {
            val cur = currentBuf() ?: fillAndGet() ?: run {
                return if (lineBuilder.isEmpty()) null else lineBuilder.toString()
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
        check(!closed) { "BufferedSuspendSource is closed" }
        return when (val m = mode) {
            is Mode.Pull -> scanLinePull(m)
            is Mode.Push -> scanLinePush(m)
        }
    }

    /** Pull-mode scanLine: scans the single internal buffer for LF. */
    private suspend fun scanLinePull(m: Mode.Pull): BufSlice? {
        val buf = m.buf
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
            if (fillPull(m) == null) {
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
    private suspend fun scanLinePush(m: Mode.Push): BufSlice? {
        val chain = m.bufferChain
        releaseConsumedBuffers(m)
        while (true) {
            if (chain.isEmpty() && fillPush(m) == null) return null

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
            if (fillPush(m) == null) {
                return if (head.readableBytes > 0) {
                    val slice = BufSlice(head, head.readerIndex, head.readableBytes)
                    head.readerIndex = head.writerIndex
                    slice
                } else null
            }

            // Line spans head and next buffer(s) — build chained BufSlice
            return crossBufferScanLine(m, head, start)
        }
    }

    /**
     * Builds a multi-segment [BufSlice] for a line that spans the buffer
     * boundary. The first segment covers the remaining bytes in [firstBuf],
     * the second covers bytes up to LF in the next buffer.
     *
     * This path is taken for < 1% of HTTP header lines.
     */
    private suspend fun crossBufferScanLine(m: Mode.Push, firstBuf: IoBuf, startOffset: Int): BufSlice? {
        val firstLength = firstBuf.writerIndex - startOffset
        firstBuf.readerIndex = firstBuf.writerIndex // consume first segment
        // Note: firstBuf is now fully consumed (readableBytes=0) but must NOT
        // be released yet — the returned BufSlice will reference it. It will be
        // released on the next scanLine/readLine/close call via releaseConsumedBuffers.
        // We do NOT call releaseConsumedBuffers here to preserve the reference.

        // Search subsequent buffers for LF.
        // Do NOT call releaseConsumedBuffers — firstBuf is consumed but still
        // referenced by the BufSlice we will return.
        val chain = m.bufferChain
        while (true) {
            if (chain.size <= 1 && fillPush(m) == null) {
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
        check(!closed) { "BufferedSuspendSource is closed" }
        val result = ByteArray(count)
        var offset = 0
        while (offset < count) {
            val cur = currentBuf() ?: fillAndGet()
                ?: throw KeelEofException("Unexpected EOF: expected $count bytes, got $offset")
            val available = cur.readableBytes.coerceAtMost(count - offset)
            cur.readByteArray(result, offset, available)
            offset += available
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
        check(!closed) { "BufferedSuspendSource is closed" }
        val cur = currentBuf() ?: fillAndGet() ?: return -1
        val available = cur.readableBytes.coerceAtMost(length)
        cur.readByteArray(dest, offset, available)
        return available
    }

    /**
     * Releases internal buffers. Does NOT close the underlying source.
     *
     * Pull mode: releases the single internal 8 KiB buffer.
     * Push mode: releases all engine-owned buffers in the chain.
     *
     * Safe to call multiple times (idempotent via `closed` flag).
     * Calling read methods after close is undefined behaviour.
     */
    override fun close() {
        if (!closed) {
            closed = true
            when (val m = mode) {
                is Mode.Pull -> m.buf.release()
                is Mode.Push -> {
                    for (buf in m.bufferChain) buf.release()
                    m.bufferChain.clear()
                    m.cachedHead = null
                }
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
