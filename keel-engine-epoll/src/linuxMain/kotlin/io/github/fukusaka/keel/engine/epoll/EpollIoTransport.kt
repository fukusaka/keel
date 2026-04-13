package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.pipeline.IoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.resume
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
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.shutdown
import platform.posix.write
import kotlinx.cinterop.ByteVar
import posix_socket.keel_writev

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
    override val allocator: BufferAllocator,
) : IoTransport {

    private var _open = true
    override val isOpen: Boolean get() = _open
    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop

    // --- Read path ---

    override var onRead: ((IoBuf) -> Unit)? = null
    override var onReadClosed: (() -> Unit)? = null

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value && _open) armRead()
        }

    private fun armRead() {
        if (!_open) return
        eventLoop.registerCallback(fd, EpollEventLoop.Interest.READ) {
            onReadable()
        }
    }

    private fun onReadable() {
        if (!_open) return
        val buf = allocator.allocate(IoTransport.DEFAULT_READ_BUFFER_SIZE)
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val n = read(fd, ptr, buf.writableBytes.convert())
        when {
            n > 0 -> {
                buf.writerIndex += n.toInt()
                onRead?.invoke(buf)
                armRead()
            }
            n == 0L -> {
                buf.release()
                onReadClosed?.invoke()
            }
            else -> {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    buf.release()
                    armRead()
                } else {
                    buf.release()
                    onReadClosed?.invoke()
                }
            }
        }
    }

    // --- Lifecycle ---

    private var outputShutdown = false

    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            shutdown(fd, SHUT_WR)
        }
    }

    // --- Write path ---

    private val pendingWrites = mutableListOf<PendingWrite>()

    override var onFlushComplete: (() -> Unit)? = null

    // --- Write backpressure ---

    private var pendingBytes: Int = 0
    private var _writable: Boolean = true
    override val isWritable: Boolean get() = _writable
    override var onWritabilityChanged: ((Boolean) -> Unit)? = null

    private fun updatePendingBytes(delta: Int) {
        pendingBytes += delta
        if (_writable && pendingBytes >= IoTransport.DEFAULT_HIGH_WATER_MARK) {
            _writable = false
            onWritabilityChanged?.invoke(false)
        } else if (!_writable && pendingBytes < IoTransport.DEFAULT_LOW_WATER_MARK) {
            _writable = true
            onWritabilityChanged?.invoke(true)
        }
    }

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
        updatePendingBytes(bytes)
    }

    override fun flush(): Boolean {
        if (pendingWrites.isEmpty()) return true
        if (pendingWrites.size == 1) {
            return flushSingle(pendingWrites.removeFirst())
        }
        return flushGather()
    }

    /**
     * Releases all pending write buffers, unregisters from epoll, and
     * closes the socket fd. Idempotent.
     */
    override fun close() {
        if (!_open) return
        _open = false
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        _writable = true
        eventLoop.cleanupFd(fd)
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
                    updatePendingBytes(-written)
                    registerWriteCallback()
                    return false
                }
                pw.buf.release()
                updatePendingBytes(-pw.length)
                return true
            }
        }
        pw.buf.release()
        updatePendingBytes(-pw.length)
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
                updatePendingBytes(-totalBytes)
                return true
            }
            writtenBytes = n.toInt()
        }

        if (writtenBytes >= totalBytes) {
            for (pw in pendingWrites) pw.buf.release()
            pendingWrites.clear()
            updatePendingBytes(-totalBytes)
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
        updatePendingBytes(-writtenBytes)
        registerWriteCallback()
        return false
    }

    private var flushContinuation: kotlinx.coroutines.CancellableContinuation<Unit>? = null

    /** Registers EPOLLOUT callback on the EventLoop to retry flush when the socket becomes writable. */
    private fun registerWriteCallback() {
        eventLoop.registerCallback(fd, EpollEventLoop.Interest.WRITE) {
            val done = flush()
            if (done) {
                flushContinuation?.let { cont ->
                    flushContinuation = null
                    cont.resume(Unit)
                }
                onFlushComplete?.invoke()
            }
        }
    }

    override suspend fun awaitPendingFlush() {
        if (pendingWrites.isEmpty()) return
        kotlinx.coroutines.suspendCancellableCoroutine { cont ->
            flushContinuation = cont
            cont.invokeOnCancellation { flushContinuation = null }
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
