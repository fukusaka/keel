package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.BufSlice
import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf

/**
 * Buffered wrapper providing readLine/readByte utilities over either a
 * pull-model [SuspendSource] or a push-model [OwnedSuspendSource].
 *
 * Both modes manage a **chain of [IoBuf]s** holding unconsumed data. New
 * data is appended at the tail; fully consumed buffers are released from
 * the head. The two modes differ only in how a buffer is acquired:
 *
 * **Pull mode**: each refill allocates an 8 KiB [IoBuf] and reads into it
 * once via [SuspendSource.read]. The filled buffer is appended to the
 * chain; once drained it is released back to the allocator.
 * ```
 * kernel → IoBuf (zero-copy via Channel.read) → appended to chain
 *   → readByte/readLine/scanLine consume from the chain (no copy)
 *   → drained buffers released back to the allocator
 * ```
 *
 * **Push mode**: each refill takes an engine-owned [IoBuf] delivered by
 * [OwnedSuspendSource.readOwned]. No allocation, no copy.
 * ```
 * kernel → engine-owned IoBuf (zero-copy via multishot recv) → chain
 *   → readByte/readLine/scanLine consume directly from the chain
 *   → fully consumed buffers released back to the engine
 * ```
 *
 * Because both modes use the same chain, neither compacts an internal
 * buffer — a refill never moves bytes; it appends a fresh segment and
 * drops drained ones. Suspend calls occur only when the chain is empty
 * and new data is needed. Typical HTTP request parsing suspends 1-2
 * times per request.
 *
 * **Ownership**: this class does NOT own the underlying source. Closing
 * this wrapper releases buffered/owned buffers but does not close the
 * source. The caller is responsible for closing the source independently.
 *
 * **Thread safety**: not thread-safe. Designed for single-threaded use
 * within an EventLoop or a single coroutine scope.
 */
class BufferedSuspendSource : AutoCloseable {

    /**
     * Internal mode discriminator. Both modes manage a [chain] of buffers
     * holding unconsumed data; they differ only in how [fill] acquires
     * the next buffer.
     */
    private sealed class Mode {
        /** Chain of buffers holding unconsumed data. Head = oldest, tail = newest. */
        val chain: ArrayDeque<IoBuf> = ArrayDeque()

        /** Cached head of [chain] to avoid an ArrayDeque lookup on every readByte. */
        var cachedHead: IoBuf? = null

        /**
         * Acquires the next buffer of data and appends it to [chain].
         *
         * @return the newly appended buffer, or null on EOF.
         */
        abstract suspend fun fill(): IoBuf?

        /** Pull mode: allocates a buffer and reads from a [SuspendSource] into it. */
        class Pull(
            private val source: SuspendSource,
            private val allocator: BufferAllocator,
        ) : Mode() {
            override suspend fun fill(): IoBuf? {
                val buf = allocator.allocate(BUFFER_SIZE)
                val n = source.read(buf)
                if (n <= 0) {
                    buf.release()
                    return null
                }
                chain.addLast(buf)
                return buf
            }
        }

        /** Push mode: requests an engine-owned buffer from an [OwnedSuspendSource]. */
        class Push(private val pushSource: OwnedSuspendSource) : Mode() {
            override suspend fun fill(): IoBuf? {
                val owned = pushSource.readOwned() ?: return null
                chain.addLast(owned)
                return owned
            }
        }
    }

    private val mode: Mode
    private var eof = false
    private var closed = false
    private val lineBuilder = StringBuilder(INITIAL_LINE_CAPACITY)

    /**
     * Pull-mode constructor: wraps a [SuspendSource]. Each refill allocates
     * an 8 KiB buffer from [allocator] and reads into it once.
     *
     * @param source The underlying [SuspendSource] to read from.
     * @param allocator Buffer allocator for refill buffers.
     */
    constructor(source: SuspendSource, allocator: BufferAllocator) {
        this.mode = Mode.Pull(source, allocator)
    }

    /**
     * Push-mode constructor: reads engine-owned [IoBuf]s from a [OwnedSuspendSource].
     *
     * No buffer is allocated. Engine-owned buffers are consumed directly
     * and released back to the engine when fully read.
     *
     * @param pushSource The push-model source delivering engine-owned buffers.
     */
    constructor(pushSource: OwnedSuspendSource) {
        this.mode = Mode.Push(pushSource)
    }

    // --- Internal: current buffer access ---

    /**
     * Returns the current buffer to read from, or null if the chain holds
     * no readable data. Drained head buffers are released first.
     */
    private fun currentBuf(): IoBuf? {
        // Fast path: cached head still has data — skip the ArrayDeque lookup.
        val cached = mode.cachedHead
        if (cached != null && cached.readableBytes > 0) return cached
        // Slow path: drop drained buffers, advance to the next one.
        releaseConsumedBuffers()
        val head = mode.chain.firstOrNull()
        mode.cachedHead = head
        return head
    }

    /** Releases fully consumed buffers at the front of the chain. */
    private fun releaseConsumedBuffers() {
        val chain = mode.chain
        while (chain.isNotEmpty() && chain.first().readableBytes == 0) {
            val released = chain.removeFirst()
            if (mode.cachedHead === released) mode.cachedHead = null
            released.release()
        }
    }

    // --- Internal: fill ---

    /**
     * Refills data and returns the buffer containing it, or null on EOF.
     * Combines fill + EOF bookkeeping so callers can write
     * `currentBuf() ?: fillAndGet() ?: <eof>`.
     */
    private suspend fun fillAndGet(): IoBuf? {
        if (eof) return null
        val buf = mode.fill()
        if (buf == null) {
            eof = true
            return null
        }
        return buf
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
     * Scans the buffer chain for a newline. If not found, refills and
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
     * [BufSlice] pointing directly into the buffer chain (zero-copy).
     *
     * Returns a single-segment [BufSlice] when the line fits in one buffer
     * (99% of cases), or a multi-segment [BufSlice] chain when the line
     * spans two buffers (< 1%).
     *
     * The returned BufSlice is valid until the next [scanLine], [readLine],
     * [readByte], [readByteArray], [readAtMostTo], or [close] call.
     *
     * @return the line without the line terminator, or null on EOF.
     * @throws IllegalStateException if this source has been [close]d.
     */
    suspend fun scanLine(): BufSlice? {
        check(!closed) { "BufferedSuspendSource is closed" }
        val chain = mode.chain
        releaseConsumedBuffers()
        if (chain.isEmpty() && fillAndGet() == null) return null

        val head = chain.first()
        val start = head.readerIndex

        // Scan the head buffer for LF.
        for (i in start until head.writerIndex) {
            if (head.getByte(i) == LF) {
                var lineEnd = i
                if (lineEnd > start && head.getByte(lineEnd - 1) == CR) lineEnd--
                val slice = BufSlice(head, start, lineEnd - start)
                head.readerIndex = i + 1
                return slice
            }
        }

        // LF not in head — need more data.
        if (fillAndGet() == null) {
            // EOF: return the trailing partial line, if any.
            return if (head.readableBytes > 0) {
                val slice = BufSlice(head, head.readerIndex, head.readableBytes)
                head.readerIndex = head.writerIndex
                slice
            } else {
                null
            }
        }

        // Line spans head and the next buffer(s) — build a chained BufSlice.
        return crossBufferScanLine(head, start)
    }

    /**
     * Builds a multi-segment [BufSlice] for a line that spans the buffer
     * boundary. The first segment covers the remaining bytes in [firstBuf],
     * the second covers bytes up to LF in the next buffer.
     *
     * This path is taken for < 1% of HTTP header lines. A line spanning
     * three or more buffers (a header line larger than two refill buffers)
     * is not supported and is left to upstream header-size limits.
     */
    private suspend fun crossBufferScanLine(firstBuf: IoBuf, startOffset: Int): BufSlice? {
        val firstLength = firstBuf.writerIndex - startOffset
        firstBuf.readerIndex = firstBuf.writerIndex // consume first segment
        // Note: firstBuf is now fully consumed (readableBytes=0) but must NOT
        // be released yet — the returned BufSlice will reference it. It is
        // released on the next scanLine/readLine/close call via
        // releaseConsumedBuffers. We do NOT call releaseConsumedBuffers here.

        val chain = mode.chain
        while (true) {
            if (chain.size <= 1 && fillAndGet() == null) {
                // EOF: return first segment only.
                return if (firstLength > 0) BufSlice(firstBuf, startOffset, firstLength) else null
            }

            val cur = chain.last() // most recently added buffer
            val curStart = cur.readerIndex
            for (i in curStart until cur.writerIndex) {
                if (cur.getByte(i) == LF) {
                    cur.readerIndex = i + 1 // consume through LF

                    // Compute second segment length, handling CR stripping.
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
     * Releases buffered buffers. Does NOT close the underlying source.
     *
     * Pull mode: releases any allocated refill buffers still in the chain.
     * Push mode: releases all engine-owned buffers in the chain.
     *
     * Safe to call multiple times (idempotent via `closed` flag).
     * Calling read methods after close is undefined behaviour.
     */
    override fun close() {
        if (!closed) {
            closed = true
            val chain = mode.chain
            for (buf in chain) buf.release()
            chain.clear()
            mode.cachedHead = null
        }
    }

    companion object {
        /** Refill buffer size for pull mode. */
        private const val BUFFER_SIZE = 8192

        /** Initial StringBuilder capacity for readLine. */
        private const val INITIAL_LINE_CAPACITY = 128
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
    }
}
