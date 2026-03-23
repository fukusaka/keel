package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.NativeBuf
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.coroutines.suspendCancellableCoroutine
import epoll.keel_writev
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.shutdown
import platform.posix.write

/**
 * Snapshot of a buffered write: the [NativeBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 *
 * We record offset/length separately because [NativeBuf.readerIndex] is
 * advanced at write() time so the caller can reuse the buffer immediately.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * epoll-based [Channel] implementation for Linux.
 *
 * **Zero-copy I/O**: read/write pass [NativeBuf.unsafePointer] directly to
 * POSIX `read()`/`write()` — no intermediate ByteArray copy.
 *
 * **Write/flush separation**: [write] retains the [NativeBuf] and records
 * the byte range to send. [flush] iterates all pending writes and calls
 * POSIX `write()` for each, then releases the buffers. Uses `writev()`
 * gather-write for multiple pending buffers.
 *
 * **Async read via EventLoop**: The socket is non-blocking. When `read()`
 * returns EAGAIN, the fd is registered with the [EpollEventLoop] for
 * EPOLLIN and the coroutine suspends. The EventLoop's `epoll_wait()` loop
 * resumes the coroutine when the fd becomes readable.
 *
 * ```
 * Read path (zero-copy, async via EventLoop):
 *   POSIX read(fd) --> NativeBuf.unsafePointer + writerIndex
 *   If EAGAIN: suspendCancellableCoroutine + eventLoop.register(fd, READ)
 *   EventLoop epoll_wait() fires --> continuation.resume(Unit) --> retry read
 *
 * Write path (buffered, zero-copy flush):
 *   write(buf)  --> retain buf, record offset/length in PendingWrite
 *   flush()     --> POSIX write/writev, release buffers
 *
 * ```
 *
 * @param fd        The connected socket file descriptor (non-blocking).
 * @param eventLoop The [EpollEventLoop] for fd readiness notification.
 * @param allocator Buffer allocator for read operations.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollChannel(
    private val fd: Int,
    private val eventLoop: EpollEventLoop,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
) : Channel {

    /**
     * Returns this channel's [EpollEventLoop] as the dispatcher.
     *
     * Coroutines launched on this dispatcher execute on the EventLoop
     * thread, keeping I/O syscalls (read/write) on the same thread
     * that drives `epoll_wait()` — no cross-thread dispatch overhead.
     */
    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop

    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    /**
     * No-op for raw fd channels. Unlike Netty's `closeFuture()`, POSIX fds
     * have no kernel-level close notification. Callers should detect close
     * via `read()` returning -1 (EOF) instead.
     */
    override suspend fun awaitClosed() {}

    /**
     * Reads bytes into [buf] via zero-copy POSIX read.
     *
     * On EAGAIN (non-blocking socket has no data), registers the fd with
     * the [EpollEventLoop] for EPOLLIN and suspends. The EventLoop
     * resumes the coroutine when data is available.
     *
     * @return number of bytes read, or -1 on EOF/error.
     */
    override suspend fun read(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        while (true) {
            // Zero-copy: write directly into NativeBuf's backing memory
            val ptr = (buf.unsafePointer + buf.writerIndex)!!
            val n = read(fd, ptr, buf.writableBytes.convert())
            when {
                n > 0 -> {
                    buf.writerIndex += n.toInt()
                    return n.toInt()
                }
                n == 0L -> return -1 // EOF: peer closed the connection
                else -> {
                    val err = errno
                    if (err == EAGAIN || err == EWOULDBLOCK) {
                        // Suspend until EventLoop reports fd is readable
                        suspendCancellableCoroutine<Unit> { cont ->
                            eventLoop.register(fd, EpollEventLoop.Interest.READ, cont)
                            cont.invokeOnCancellation {
                                eventLoop.unregister(fd, EpollEventLoop.Interest.READ)
                            }
                        }
                        continue
                    }
                    return -1 // Other error (e.g. ECONNRESET)
                }
            }
        }
    }

    /**
     * Buffers a write by retaining [buf] and recording the current readable range.
     *
     * The caller's [NativeBuf.readerIndex] is advanced immediately so the
     * buffer can be reused or released by the caller. The actual POSIX write
     * happens on [flush].
     *
     * @return number of bytes buffered.
     */
    override suspend fun write(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val bytes = buf.readableBytes
        if (bytes == 0) return 0
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        return bytes
    }

    /**
     * Sends all buffered writes to the network via POSIX write.
     *
     * Each [PendingWrite] is written using zero-copy pointer access,
     * then the retained [NativeBuf] is released.
     *
     * **EAGAIN / short write handling**: Non-blocking sockets may return
     * EAGAIN (send buffer full) or a short write (fewer bytes than requested).
     * Both cases are handled by suspending on EPOLLOUT via the EventLoop
     * and retrying, using the same pattern as [read].
     *
     * **Gather write**: Uses POSIX `writev()` for multiple pending buffers.
     * On partial writev, completed buffers are released and the remaining
     * partial buffer falls through to the single-write loop.
     */
    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        if (pendingWrites.isEmpty()) return

        if (pendingWrites.size == 1) {
            flushSingle(pendingWrites[0])
        } else {
            flushGather()
        }
        pendingWrites.clear()
    }

    /**
     * Writes a single [PendingWrite] with EAGAIN and short write handling.
     *
     * Loops until all bytes are written, suspending on EPOLLOUT when
     * the send buffer is full (EAGAIN/EWOULDBLOCK).
     */
    private suspend fun flushSingle(pw: PendingWrite) {
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
                    // Send buffer full — suspend until fd is writable
                    suspendCancellableCoroutine<Unit> { cont ->
                        eventLoop.register(fd, EpollEventLoop.Interest.WRITE, cont)
                        cont.invokeOnCancellation {
                            eventLoop.unregister(fd, EpollEventLoop.Interest.WRITE)
                        }
                    }
                    continue
                }
                break // Other error (e.g. EPIPE, ECONNRESET)
            }
        }
        pw.buf.release()
    }

    /**
     * Writes multiple pending buffers using gather write (writev).
     *
     * Attempts a single `writev()` syscall for all buffers. If writev
     * completes fully, all buffers are released. On partial write or
     * EAGAIN, completed buffers are released and the remaining data
     * falls through to [flushSingle] for per-buffer retry.
     */
    private suspend fun flushGather() {
        // First attempt: writev for all pending buffers
        val totalBytes = pendingWrites.sumOf { it.length }
        val writtenBytes: Int

        memScoped {
            val bases = allocArray<CPointerVar<ByteVar>>(pendingWrites.size)
            val lens = allocArray<ULongVar>(pendingWrites.size)
            for ((i, pw) in pendingWrites.withIndex()) {
                bases[i] = (pw.buf.unsafePointer + pw.offset)!!
                lens[i] = pw.length.convert()
            }
            val n = keel_writev(fd, bases.reinterpret(), lens.reinterpret(), pendingWrites.size)
            if (n < 0) {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    // Nothing written — suspend, then fall through to per-buffer writes
                    suspendCancellableCoroutine<Unit> { cont ->
                        eventLoop.register(fd, EpollEventLoop.Interest.WRITE, cont)
                        cont.invokeOnCancellation {
                            eventLoop.unregister(fd, EpollEventLoop.Interest.WRITE)
                        }
                    }
                    // After wakeup, flush each buffer individually
                    for (pw in pendingWrites) flushSingle(pw)
                    return
                }
                // Other error — release all buffers and return
                for (pw in pendingWrites) pw.buf.release()
                return
            }
            writtenBytes = n.toInt()
        }

        if (writtenBytes >= totalBytes) {
            // All data written — release all buffers
            for (pw in pendingWrites) pw.buf.release()
            return
        }

        // Partial writev: release fully-written buffers, flush remainder
        var consumed = 0
        for (pw in pendingWrites) {
            if (consumed + pw.length <= writtenBytes) {
                // This buffer was fully written
                consumed += pw.length
                pw.buf.release()
            } else {
                // Partially or not written — flush remaining bytes
                val alreadyWritten = (writtenBytes - consumed).coerceAtLeast(0)
                flushSingle(PendingWrite(pw.buf, pw.offset + alreadyWritten, pw.length - alreadyWritten))
                consumed = writtenBytes // Skip further partial calculation
            }
        }
    }

    /**
     * Sends TCP FIN to the peer via `shutdown(fd, SHUT_WR)`.
     * The read side remains open so the peer's remaining data can be consumed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            shutdown(fd, SHUT_WR)
        }
    }

    /**
     * Closes the socket and releases all pending writes.
     * Unflushed data is discarded (buffers are released without sending).
     */
    override fun close() {
        if (_open) {
            _open = false
            _active = false
            // Release retained buffers that were never flushed
            for (pw in pendingWrites) {
                pw.buf.release()
            }
            pendingWrites.clear()
            close(fd)
        }
    }

}
