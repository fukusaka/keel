package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.BufferAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.NativeBuf
import io.github.fukusaka.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kqueue.keel_ev_set
import platform.darwin.EV_ADD
import platform.darwin.EVFILT_READ
import platform.darwin.kevent
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.shutdown
import platform.posix.timespec
import platform.posix.write
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kqueue.keel_writev

/**
 * Snapshot of a buffered write: the [NativeBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 *
 * We record offset/length separately because [NativeBuf.readerIndex] is
 * advanced at write() time so the caller can reuse the buffer immediately.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * kqueue-based [Channel] implementation for macOS.
 *
 * **Zero-copy I/O**: read/write pass [NativeBuf.unsafePointer] directly to
 * POSIX `read()`/`write()` — no intermediate ByteArray copy.
 *
 * **Write/flush separation**: [write] retains the [NativeBuf] and records
 * the byte range to send. [flush] iterates all pending writes and calls
 * POSIX `write()` for each, then releases the buffers. This design enables
 * future writev/gather-write optimisation without API changes.
 *
 * **Read blocking with kqueue wait**: the socket is non-blocking. When
 * `read()` returns EAGAIN, we wait on kqueue (EVFILT_READ) with a 5-second
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
 *     --> register fd with kqueue (EVFILT_READ, lazy)
 *     --> kevent(kqFd, timeout=5s)
 *     --> retry read(fd)
 *
 * Codec bridge (byte-by-byte copy, acceptable for codec layer):
 *   asSource() --> ChannelSource --> NativeBuf --> kotlinx-io Buffer
 *   asSink()   --> ChannelSink   --> kotlinx-io Buffer --> NativeBuf
 * ```
 *
 * @param fd        The connected socket file descriptor.
 * @param kqFd      The kqueue file descriptor shared from [KqueueEngine],
 *                   used for EAGAIN read-wait and future event-driven I/O.
 * @param allocator Buffer allocator for [asSource]/[asSink] bridge.
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueueChannel(
    private val fd: Int,
    private val kqFd: Int,
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
     * kqueue for EVFILT_READ and waits up to 5 seconds before retrying.
     * The fd is registered lazily on first read to avoid unnecessary
     * kevent syscalls when data is already available.
     *
     * @return number of bytes read, or -1 on EOF/error.
     */
    internal fun readBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        // Lazy registration: register with kqueue on first read so that
        // we can kevent-wait when EAGAIN is returned.
        if (!registeredForRead) {
            memScoped {
                val kev = alloc<kevent>()
                keel_ev_set(
                    kev.ptr, fd.convert(), EVFILT_READ.convert(),
                    EV_ADD.convert(), 0u, 0, null,
                )
                kevent(kqFd, kev.ptr, 1, null, 0, null)
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
                        // No data available yet — wait for kqueue readiness
                        memScoped {
                            val eventList = allocArray<kevent>(1)
                            val timeout = alloc<timespec>()
                            timeout.tv_sec = 5
                            timeout.tv_nsec = 0
                            kevent(kqFd, null, 0, eventList, 1, timeout.ptr)
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
     * Uses POSIX `writev()` to send all pending buffers in a single
     * syscall (gather write) when multiple writes are buffered,
     * reducing context switches compared to individual `write()` calls.
     * Falls back to single `write()` for one-buffer flushes.
     */
    internal fun flushBlocking() {
        check(_open) { "Channel is closed" }
        if (pendingWrites.isEmpty()) return

        if (pendingWrites.size == 1) {
            val pw = pendingWrites[0]
            val ptr = (pw.buf.unsafePointer + pw.offset)!!
            write(fd, ptr, pw.length.convert())
            pw.buf.release()
        } else {
            // Gather write: send all buffers in one writev syscall via C wrapper.
            // keel_writev accepts parallel arrays of base pointers and lengths,
            // builds iovec internally to avoid exposing struct iovec to Kotlin.
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
            // Release retained buffers that were never flushed
            for (pw in pendingWrites) {
                pw.buf.release()
            }
            pendingWrites.clear()
            close(fd)
        }
    }
}
