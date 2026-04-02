package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.close
import platform.posix.errno
import platform.posix.write
import kotlinx.cinterop.ByteVar
import kqueue.keel_writev

/**
 * Non-suspend [IoTransport] for kqueue pipeline channels.
 *
 * Buffers outbound [IoBuf] writes and flushes them via POSIX `write()` / `writev()`.
 * When the send buffer is full (EAGAIN), registers EVFILT_WRITE with the [eventLoop]
 * and retries via [onFlushComplete] callback — no coroutine suspension involved.
 *
 * **Thread safety**: all methods must be called on the [eventLoop] thread.
 *
 * **Buffer lifecycle**: `write()` retains the buffer; `flush()` releases it
 * after transmission or on error.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueIoTransport(
    private val fd: Int,
    private val eventLoop: KqueueEventLoop,
) : IoTransport {

    private val pendingWrites = mutableListOf<PendingWrite>()

    override var onFlushComplete: (() -> Unit)? = null

    /**
     * Buffers [buf] for the next [flush] call.
     *
     * Captures (readerIndex, readableBytes) snapshot and retains the buffer.
     * The caller's readerIndex is advanced immediately so it can reuse the buf.
     */
    override fun write(buf: IoBuf) {
        val bytes = buf.readableBytes
        if (bytes == 0) return
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
    }

    /**
     * Attempts to send all pending writes via POSIX `write()`.
     *
     * @return `true` if all data was sent synchronously, `false` if EAGAIN
     *         was encountered and an async EVFILT_WRITE callback is pending.
     */
    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true

        if (pendingWrites.size == 1) {
            return flushSingle(pendingWrites.removeFirst())
        }
        return flushGather()
    }

    override fun close() {
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        close(fd)
    }

    // --- Single-buffer flush ---

    /**
     * Writes a single buffer. On EAGAIN, registers EVFILT_WRITE callback
     * to retry with the remaining bytes.
     */
    private fun flushSingle(pw: PendingWrite): Boolean {
        var written = 0
        while (written < pw.length) {
            val ptr = (pw.buf.unsafePointer + pw.offset + written)!!
            val remaining = (pw.length - written).convert<ULong>()
            val n = write(fd, ptr, remaining)
            if (n >= 0) {
                written += n.toInt()
            } else {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    // Defer remainder: re-enqueue partial PendingWrite and register WRITE interest.
                    val remainder = PendingWrite(pw.buf, pw.offset + written, pw.length - written)
                    pendingWrites.add(0, remainder)
                    registerWriteCallback()
                    return false
                }
                // Other error (EPIPE, ECONNRESET) — release and drop.
                pw.buf.release()
                return true
            }
        }
        pw.buf.release()
        return true
    }

    // --- Gather-write flush ---

    /**
     * Writes multiple pending buffers via `writev()`. Falls back to
     * single-buffer retry on partial write or EAGAIN.
     */
    private fun flushGather(): Boolean {
        val totalBytes = pendingWrites.sumOf { it.length }
        val writtenBytes: Int

        memScoped {
            val count = pendingWrites.size
            val bases = allocArray<CPointerVar<ByteVar>>(count)
            val lens = allocArray<ULongVar>(count)
            for ((i, pw) in pendingWrites.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)!!
                lens[i] = pw.length.convert()
            }
            val n = keel_writev(fd, bases.reinterpret(), lens.reinterpret(), count)
            if (n < 0) {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    // Nothing written — register WRITE and retry all later.
                    registerWriteCallback()
                    return false
                }
                // Other error — release all and return.
                for (pw in pendingWrites) pw.buf.release()
                pendingWrites.clear()
                return true
            }
            writtenBytes = n.toInt()
        }

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            pendingWrites.clear()
            return true
        }

        // Partial writev: release fully-written buffers, adjust the split buffer.
        val remaining = mutableListOf<PendingWrite>()
        var consumed = 0
        for (pw in pendingWrites) {
            if (consumed + pw.length <= writtenBytes) {
                consumed += pw.length
                pw.buf.release()
            } else {
                val alreadyWritten = (writtenBytes - consumed).coerceAtLeast(0)
                remaining.add(PendingWrite(pw.buf, pw.offset + alreadyWritten, pw.length - alreadyWritten))
                consumed += pw.length
            }
        }
        pendingWrites.clear()
        pendingWrites.addAll(remaining)
        registerWriteCallback()
        return false
    }

    // --- Async write readiness ---

    private fun registerWriteCallback() {
        eventLoop.registerCallback(fd, KqueueEventLoop.Interest.WRITE) {
            // Retry flush when fd becomes writable.
            val done = flush()
            if (done) {
                onFlushComplete?.invoke()
            }
        }
    }

    internal class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)
}
