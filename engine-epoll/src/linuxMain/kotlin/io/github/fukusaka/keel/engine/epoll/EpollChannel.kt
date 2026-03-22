package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.NativeBuf
import io.github.fukusaka.keel.core.SocketAddress
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
import kotlinx.io.RawSink
import kotlinx.io.RawSource
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
 * Codec bridge (byte-by-byte copy, acceptable for codec layer):
 *   asSource() --> ChannelSource --> NativeBuf --> kotlinx-io Buffer
 *   asSink()   --> ChannelSink   --> kotlinx-io Buffer --> NativeBuf
 * ```
 *
 * @param fd        The connected socket file descriptor (non-blocking).
 * @param eventLoop The [EpollEventLoop] for fd readiness notification.
 * @param allocator Buffer allocator for [asSource]/[asSink] bridge.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollChannel(
    private val fd: Int,
    private val eventLoop: EpollEventLoop,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
) : Channel {

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
     * Uses POSIX `writev()` to send all pending buffers in a single
     * syscall (gather write) when multiple writes are buffered.
     * Falls back to single `write()` for one-buffer flushes.
     */
    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        if (pendingWrites.isEmpty()) return

        if (pendingWrites.size == 1) {
            val pw = pendingWrites[0]
            val ptr = (pw.buf.unsafePointer + pw.offset)!!
            write(fd, ptr, pw.length.convert())
            pw.buf.release()
        } else {
            memScoped {
                val bases = allocArray<CPointerVar<ByteVar>>(pendingWrites.size)
                val lens = allocArray<ULongVar>(pendingWrites.size)
                for ((i, pw) in pendingWrites.withIndex()) {
                    bases[i] = (pw.buf.unsafePointer + pw.offset)!!
                    lens[i] = pw.length.convert()
                }
                keel_writev(fd, bases.reinterpret(), lens.reinterpret(), pendingWrites.size)
            }
            for (pw in pendingWrites) pw.buf.release()
        }
        pendingWrites.clear()
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

    override fun asSource(): RawSource = ChannelSource(this, allocator)

    override fun asSink(): RawSink = ChannelSink(this, allocator)

    /**
     * Closes the socket and releases all pending writes.
     * Unflushed data is discarded (buffers are released without sending).
     */
    override fun close() {
        if (_open) {
            _open = false
            _active = false
            for (pw in pendingWrites) {
                pw.buf.release()
            }
            pendingWrites.clear()
            close(fd)
        }
    }

    companion object {
        /**
         * Buffer size for [ChannelSource]/[ChannelSink] codec bridge.
         * Matches the default kotlinx-io segment size.
         */
        internal const val CODEC_BUFFER_SIZE = 8192
    }
}
