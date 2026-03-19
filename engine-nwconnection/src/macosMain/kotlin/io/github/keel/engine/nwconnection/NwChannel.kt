package io.github.keel.engine.nwconnection

import io.github.keel.core.BufferAllocator
import io.github.keel.core.Channel
import io.github.keel.core.NativeBuf
import io.github.keel.core.SocketAddress
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import nwconnection.keel_nw_read
import nwconnection.keel_nw_shutdown_output
import nwconnection.keel_nw_write
import platform.Network.nw_connection_cancel
import platform.Network.nw_connection_t
import platform.posix.int32_tVar
import platform.posix.uint32_tVar

/**
 * Snapshot of a buffered write: the [NativeBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 *
 * We record offset/length separately because [NativeBuf.readerIndex] is
 * advanced at write() time so the caller can reuse the buffer immediately.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * NWConnection-based [Channel] implementation for macOS.
 *
 * **Read path (copy from dispatch_data_t)**:
 * Unlike kqueue/epoll which use zero-copy POSIX `read()` directly into
 * [NativeBuf], NWConnection delivers received data as `dispatch_data_t`.
 * The C wrapper [keel_nw_read] copies data segment-by-segment via
 * `dispatch_data_apply` + `memcpy`. This is an accepted limitation for
 * Phase 5 — measured overhead is ~0.1–0.4 us per small packet.
 * See nwconnection.def for details and Phase 6+ zero-copy plans.
 *
 * **Write/flush separation**: [write] retains the [NativeBuf] and records
 * the byte range to send. [flush] iterates all pending writes and calls
 * [keel_nw_write] for each, then releases the buffers. This design matches
 * [KqueueChannel] and enables future gather-write optimisation.
 *
 * Phase (a): all suspend methods delegate to internal blocking counterparts.
 *
 * ```
 * Read path (copy):
 *   nw_connection_receive --> dispatch_data_t
 *     --> dispatch_data_apply + memcpy --> NativeBuf
 *
 * Write path (buffered, flush via NWConnection send):
 *   write(buf)  --> retain buf, record offset/length in PendingWrite
 *   write(buf2) --> retain buf2, append to pendingWrites
 *   flush()     --> keel_nw_write(conn, buf ptr, length)
 *                   keel_nw_write(conn, buf2 ptr, length)
 *                   release buf, release buf2
 *
 * Codec bridge (byte-by-byte copy, acceptable for codec layer):
 *   asSource() --> ChannelSource --> NativeBuf --> kotlinx-io Buffer
 *   asSink()   --> ChannelSink   --> kotlinx-io Buffer --> NativeBuf
 * ```
 *
 * @param conn       The NWConnection handle for this channel.
 * @param allocator  Buffer allocator for [asSource]/[asSink] bridge.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NwChannel(
    private val conn: nw_connection_t,
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

    /** Phase (a): no-op. Will suspend until closed in Phase (b). */
    override suspend fun awaitClosed() {}

    override suspend fun read(buf: NativeBuf): Int = readBlocking(buf)

    override suspend fun write(buf: NativeBuf): Int = writeBlocking(buf)

    override suspend fun flush() = flushBlocking()

    /**
     * Reads bytes into [buf] via NWConnection receive + memcpy.
     *
     * Calls the C wrapper [keel_nw_read] which blocks on a dispatch
     * semaphore until the receive completion handler fires. Data is
     * copied from dispatch_data_t into [buf] segment-by-segment.
     *
     * @return number of bytes read, or -1 on EOF/error.
     */
    internal fun readBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        memScoped {
            val outLen = alloc<uint32_tVar>()
            val outComplete = alloc<int32_tVar>()
            val ptr = (buf.unsafePointer + buf.writerIndex)!!

            val rc = keel_nw_read(
                conn, ptr, buf.writableBytes.toUInt(),
                outLen.ptr, outComplete.ptr,
            )
            if (rc < 0) return -1

            val n = outLen.value.toInt()
            if (n > 0) buf.writerIndex += n

            // EOF: peer closed the connection
            if (n == 0 && outComplete.value != 0) return -1
            return n
        }
    }

    /**
     * Buffers a write by retaining [buf] and recording the current readable range.
     *
     * The caller's [NativeBuf.readerIndex] is advanced immediately so the
     * buffer can be reused or released by the caller. The actual NWConnection
     * send happens on [flushBlocking].
     *
     * @return number of bytes buffered.
     */
    internal fun writeBlocking(buf: NativeBuf): Int {
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
     * Sends all buffered writes to the network via NWConnection.
     *
     * Each [PendingWrite] is sent using [keel_nw_write] which creates
     * a dispatch_data_t and blocks until the send completes.
     * The retained [NativeBuf] is released after each send.
     */
    internal fun flushBlocking() {
        check(_open) { "Channel is closed" }
        for (pw in pendingWrites) {
            val ptr = (pw.buf.unsafePointer + pw.offset)!!
            keel_nw_write(conn, ptr, pw.length.toUInt())
            pw.buf.release()
        }
        pendingWrites.clear()
    }

    /**
     * Sends TCP FIN to the peer via NWConnection.
     * The read side remains open so the peer's remaining data can be consumed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            keel_nw_shutdown_output(conn)
        }
    }

    override fun asSource(): RawSource = ChannelSource(this, allocator)

    override fun asSink(): RawSink = ChannelSink(this, allocator)

    /**
     * Cancels the NWConnection and releases all pending writes.
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
            nw_connection_cancel(conn)
        }
    }
}
