package io.github.keel.engine.epoll

import io.github.keel.core.BufferAllocator
import io.github.keel.core.Channel
import io.github.keel.core.NativeBuf
import io.github.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import platform.linux.EPOLLIN
import platform.linux.EPOLL_CTL_ADD
import platform.linux.epoll_ctl
import platform.linux.epoll_event
import platform.linux.epoll_wait
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
 * POSIX `write()` for each, then releases the buffers. This design enables
 * future writev/gather-write optimisation without API changes.
 *
 * **Read blocking with epoll wait**: the socket is non-blocking. When
 * `read()` returns EAGAIN, we wait on epoll (EPOLLIN) with a 5-second
 * timeout before retrying. This avoids busy-wait while keeping the socket
 * in non-blocking mode for compatibility with the event loop in Phase (b).
 *
 * Phase (a): all suspend methods delegate to internal blocking counterparts.
 *
 * ```
 * Read path (zero-copy):
 *   POSIX read(fd) --> NativeBuf.unsafePointer + writerIndex
 *                      (data lands directly in native memory)
 *
 * Write path (buffered, zero-copy flush):
 *   write(buf)  --> retain buf, record offset/length in PendingWrite
 *   write(buf2) --> retain buf2, append to pendingWrites
 *   flush()     --> POSIX write(fd, buf.unsafePointer + offset, length)
 *                   POSIX write(fd, buf2.unsafePointer + offset, length)
 *                   release buf, release buf2
 *
 * Read wait (EAGAIN handling):
 *   read(fd) returns EAGAIN
 *     --> register fd with epoll (EPOLLIN, lazy)
 *     --> epoll_wait(epFd, timeout=5s)
 *     --> retry read(fd)
 *
 * Codec bridge (byte-by-byte copy, acceptable for codec layer):
 *   asSource() --> ChannelSource --> NativeBuf --> kotlinx-io Buffer
 *   asSink()   --> ChannelSink   --> kotlinx-io Buffer --> NativeBuf
 * ```
 *
 * @param fd        The connected socket file descriptor.
 * @param epFd      The epoll file descriptor shared from [EpollEngine],
 *                   used for EAGAIN read-wait and future event-driven I/O.
 * @param allocator Buffer allocator for [asSource]/[asSink] bridge.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollChannel(
    private val fd: Int,
    private val epFd: Int,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
) : Channel {

    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false
    private var registeredForRead = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    /** Phase (a): no-op. Will suspend until closed in Phase (b). */
    override suspend fun awaitClosed() {}

    override suspend fun read(buf: NativeBuf): Int = readBlocking(buf)

    override suspend fun write(buf: NativeBuf): Int = writeBlocking(buf)

    override suspend fun flush() = flushBlocking()

    /**
     * Reads bytes into [buf] via zero-copy POSIX read.
     *
     * On EAGAIN (non-blocking socket has no data), registers the fd with
     * epoll for EPOLLIN and waits up to 5 seconds before retrying.
     * The fd is registered lazily on first read to avoid unnecessary
     * epoll_ctl syscalls when data is already available.
     *
     * @return number of bytes read, or -1 on EOF/error.
     */
    internal fun readBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        // Lazy registration: register with epoll on first read so that
        // we can epoll_wait when EAGAIN is returned.
        if (!registeredForRead) {
            memScoped {
                val ev = alloc<epoll_event>()
                ev.events = EPOLLIN.toUInt()
                ev.data.fd = fd
                epoll_ctl(epFd, EPOLL_CTL_ADD, fd, ev.ptr)
            }
            registeredForRead = true
        }

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
                        // No data available yet — wait for epoll readiness
                        memScoped {
                            val eventList = allocArray<epoll_event>(1)
                            epoll_wait(epFd, eventList, 1, 5000)
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
     * happens on [flushBlocking].
     *
     * @return number of bytes buffered.
     */
    internal fun writeBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val bytes = buf.readableBytes
        if (bytes == 0) return 0
        // Capture the current read position before advancing
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
     * Phase (b) will replace individual write() calls with writev()
     * for gather-write optimisation.
     */
    internal fun flushBlocking() {
        check(_open) { "Channel is closed" }
        for (pw in pendingWrites) {
            // Zero-copy: read directly from NativeBuf's backing memory
            val ptr = (pw.buf.unsafePointer + pw.offset)!!
            write(fd, ptr, pw.length.convert())
            pw.buf.release()
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
            // Release retained buffers that were never flushed
            for (pw in pendingWrites) {
                pw.buf.release()
            }
            pendingWrites.clear()
            close(fd)
        }
    }
}
