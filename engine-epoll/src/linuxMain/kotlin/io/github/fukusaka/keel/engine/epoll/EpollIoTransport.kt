package io.github.fukusaka.keel.engine.epoll

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
import epoll.keel_writev

/**
 * Non-suspend [IoTransport] for epoll pipeline channels.
 *
 * Buffers outbound [IoBuf] writes and flushes them via POSIX `write()` / `writev()`.
 * When the send buffer is full (EAGAIN), registers EPOLLOUT with the [eventLoop]
 * and retries via [onFlushComplete] callback — no coroutine suspension involved.
 *
 * **Thread safety**: all methods must be called on the [eventLoop] thread.
 *
 * **Buffer lifecycle**: `write()` retains the buffer; `flush()` releases it
 * after transmission or on error.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollIoTransport(
    private val fd: Int,
    private val eventLoop: EpollEventLoop,
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

    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        if (pendingWrites.size == 1) {
            return flushSingle(pendingWrites.removeFirst())
        }
        return flushGather()
    }

    /**
     * Releases all pending write buffers and closes the socket fd.
     *
     * Unsent data is discarded. Does NOT unregister any pending EPOLLOUT
     * callback. Idempotent for buffer release; caller must ensure single close.
     */
    override fun close() {
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        close(fd)
    }

    /**
     * Writes a single [PendingWrite] via POSIX `write()`.
     *
     * On EAGAIN, re-enqueues the remainder and registers EPOLLOUT
     * callback for async retry.
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
                    val remainder = PendingWrite(pw.buf, pw.offset + written, pw.length - written)
                    pendingWrites.add(0, remainder)
                    registerWriteCallback()
                    return false
                }
                pw.buf.release()
                return true
            }
        }
        pw.buf.release()
        return true
    }

    /**
     * Writes multiple pending buffers via `writev()` (gather write).
     *
     * On partial write, fully-written buffers are released and the remainder
     * is re-enqueued with EPOLLOUT callback for async retry.
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
                    registerWriteCallback()
                    return false
                }
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

    /** Registers EPOLLOUT callback on the EventLoop to retry flush when the socket becomes writable. */
    private fun registerWriteCallback() {
        eventLoop.registerCallback(fd, EpollEventLoop.Interest.WRITE) {
            val done = flush()
            if (done) {
                onFlushComplete?.invoke()
            }
        }
    }

    /**
     * Snapshot of a buffered write: the [IoBuf] (retained), the byte offset
     * where readable data starts, and the number of bytes to write.
     *
     * Offset/length are recorded separately because [IoBuf.readerIndex] is
     * advanced at write() time so the caller can reuse the buffer immediately.
     */
    internal class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)
}
