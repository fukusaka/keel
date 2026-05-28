package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf

/**
 * Buffered wrapper over [SuspendSink] providing writeString/writeByte utilities.
 *
 * Uses [IoBuf] instances from [allocator] for zero-copy I/O with a single,
 * uniform deferred-flush strategy across every engine:
 * ```
 * writeString/writeByte → IoBuf (buffer accumulation)
 *   → when buffer is full:
 *     IoBuf → sink.write (enqueue, ownership transfer) → allocate fresh IoBuf
 *   → when flush() is called:
 *     sink.flush → single OS write (writev) for all queued buffers
 * ```
 * Each filled buffer is handed off to [sink] (which queues it) and a fresh
 * buffer is allocated; the actual OS write is deferred to [flush]. This
 * batches a multi-buffer response into one flush and never reuses a handed-off
 * buffer, so it is safe even when [sink]'s flush runs on a different thread
 * (push-model engines: Netty / NWConnection). Every keel transport queues
 * writes (`pendingWrites`) and sends them on flush, so no engine needs a
 * separate immediate-write path. (An earlier design carried a `deferFlush`
 * flag with an immediate-write + buffer-reuse branch for Node.js, but Node's
 * transport also queues + flushes, so the flag was inconsistent dead weight
 * and was removed.)
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
 */
class BufferedSuspendSink(
    private val sink: SuspendSink,
    private val allocator: BufferAllocator,
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
            buf.writeAscii(text, pos, chunk)
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
     *
     * For payloads at or above [DIRECT_WRITE_THRESHOLD], bypasses the internal
     * scratch buffer and forwards a zero-copy [IoBuf] view of the caller's
     * array to [sink] (on platforms where [BufferAllocator.wrapBytes] returns
     * non-null, i.e. JVM and Native). This avoids the `BUFFER_SIZE`-sized chunking that would
     * otherwise split a large body into many small writes, each producing a
     * short-lived `PendingWrite`, Netty `ByteBuf`, flush promise, and listener
     * lambda — multiplying per-request allocations by the chunk count and
     * driving JVM engines into GC-dominated variance on large responses.
     *
     * The caller must not mutate [bytes] between this call returning and the
     * next [flush] completing. Any previously buffered scratch data is flushed
     * first so that on-wire ordering is preserved.
     */
    suspend fun write(bytes: ByteArray, offset: Int, length: Int) {
        if (length >= DIRECT_WRITE_THRESHOLD) {
            val wrapped = allocator.wrapBytes(bytes, offset, length)
            if (wrapped != null) {
                // Flush any scratch data first to keep ordering (headers before body).
                flushBuffer()
                // Ownership transfer: sink.write takes over `wrapped`. The OS
                // write is deferred to the caller's flush().
                sink.write(wrapped)
                return
            }
        }
        // Fallback: chunked copy through the scratch buffer. Used on Native/JS
        // and when the payload is smaller than the direct-write threshold.
        var pos = offset
        var remaining = length
        while (remaining > 0) {
            if (buf.writableBytes == 0) flushBuffer()
            val chunk = remaining.coerceAtMost(buf.writableBytes)
            buf.writeByteArray(bytes, pos, chunk)
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
     * Transfers the internal buffer's contents to the underlying sink and
     * allocates a fresh replacement, deferring the OS write to [flush].
     *
     * The old buffer remains in the transport's pending-write queue until the
     * caller's [flush] sends all accumulated buffers in a single writev()
     * syscall; the transport releases it when the flush completes. The
     * replacement is allocated BEFORE handing `buf` off so that `this.buf`
     * always points to a valid buffer — if allocate throws, the old buf is
     * still valid and [close] can release it safely.
     */
    private suspend fun flushBuffer() {
        if (buf.readableBytes > 0) {
            val oldBuf = buf
            buf = allocator.allocate(BUFFER_SIZE)
            sink.write(oldBuf) // transfers ownership of oldBuf to sink
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

        /**
         * Threshold at or above which `write(ByteArray, offset, length)` tries
         * the zero-copy direct path instead of chunking through the scratch
         * buffer. Payloads below this size are small enough that scratch
         * buffering plus syscall batching outperforms the overhead of wrapping
         * the array as an [IoBuf] (object allocation + one extra `IoBuf` trip
         * through the pipeline). Set equal to [BUFFER_SIZE] because anything
         * that cannot fit in the scratch buffer in a single step is guaranteed
         * to force at least one `flushBuffer` round-trip anyway.
         */
        private const val DIRECT_WRITE_THRESHOLD = BUFFER_SIZE
    }
}
