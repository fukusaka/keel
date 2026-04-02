package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.PushChannel
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.io.PushSuspendSource
import io.github.fukusaka.keel.io.PushToSuspendSourceAdapter
import io.github.fukusaka.keel.io.SuspendSource

// SuspendChannelSource is internal to core; pull-model fallback uses
// Channel's default asSuspendSource() via super call.
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.plus
import kotlinx.coroutines.CoroutineDispatcher
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.shutdown

/**
 * io_uring-based [Channel] implementation for Linux.
 *
 * **Thread safety**: All public methods must be called from coroutines
 * dispatched on the owning [IoUringEventLoop] (accessible via
 * [coroutineDispatcher]). The channel has no internal synchronisation;
 * concurrent access from multiple threads causes undefined behaviour.
 *
 * **Completion model**: Unlike epoll's readiness model (which requires retrying
 * the syscall on EAGAIN), io_uring delivers the operation result directly in
 * the CQE. Each read/write submits an SQE and suspends; the EventLoop resumes
 * the coroutine with the CQE result.
 *
 * **Zero-copy I/O**: read passes [IoBuf.unsafePointer] directly to
 * `IORING_OP_RECV`; flush passes IoBuf pointers to `IORING_OP_SEND` /
 * `IORING_OP_WRITEV`. No intermediate ByteArray copy.
 *
 * **Write path delegation**: write buffering and flush logic are delegated
 * to [IoUringIoTransport], which is shared with the pipeline HeadHandler.
 *
 * ```
 * Read path:
 *   submitRecv(fd, ptr, writableBytes, 0) → CQE.res
 *   CQE.res > 0 → advance writerIndex, return
 *   CQE.res = 0 → EOF; CQE.res < 0 → error
 *
 * Write path:
 *   write(buf)  → transport.write(buf) — retain + record PendingWrite
 *   flush()     → transport.flushSuspend() — submitSend / submitWritev
 * ```
 *
 * @param fd         The connected socket file descriptor.
 * @param eventLoop  The [IoUringEventLoop] for SQE submission and CQE dispatch.
 * @param transport  The [IoUringIoTransport] for write buffering and flush.
 * @param allocator  Buffer allocator for read operations.
 * @param bufferRing The [ProvidedBufferRing] for multishot recv buffer selection.
 *                   Used by [asSuspendSource] to create a push-model source.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringChannel(
    private val fd: Int,
    private val eventLoop: IoUringEventLoop,
    internal val transport: IoUringIoTransport,
    override val allocator: BufferAllocator,
    private val bufferRing: ProvidedBufferRing?,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
    private val capabilities: IoUringCapabilities = IoUringCapabilities(),
) : Channel, PushChannel {

    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop
    override val supportsDeferredFlush: Boolean get() = true

    override fun asPushSuspendSource(): PushSuspendSource {
        val ring = bufferRing
            ?: error("Push source requires provided buffer ring (kernel 5.19+)")
        return IoUringPushSource(fd, eventLoop, ring)
    }

    /**
     * Returns a [SuspendSource] for reading from this channel.
     *
     * If multishot recv and provided buffer ring are available, uses the
     * push-model [IoUringPushSource] via [PushToSuspendSourceAdapter].
     * Otherwise, falls back to the pull-model [SuspendChannelSource].
     */
    override fun asSuspendSource(): SuspendSource {
        return if (capabilities.multishotRecv && capabilities.providedBufferRing && bufferRing != null) {
            PushToSuspendSourceAdapter(IoUringPushSource(fd, eventLoop, bufferRing))
        } else {
            // Pull-model fallback: use Channel's default asSuspendSource()
            // which delegates to SuspendChannelSource (internal to core module).
            @Suppress("RedundantOverride") // intentional: dispatches to Channel default
            super<Channel>.asSuspendSource()
        }
    }

    private var _open = true
    private var _active = true
    private var outputShutdown = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    /** No kernel-level close notification for raw fds. Callers detect EOF via read() returning -1. */
    override suspend fun awaitClosed() {}

    /**
     * Reads bytes into [buf] via `IORING_OP_RECV`.
     *
     * Submits a RECV SQE targeting the IoBuf's write region and suspends until
     * the CQE arrives. Unlike epoll, there is no EAGAIN: the kernel holds the
     * operation pending until data arrives or an error occurs.
     *
     * @return number of bytes read, or -1 on EOF (CQE.res=0) or error.
     * @throws IllegalStateException if the channel is closed.
     */
    override suspend fun read(buf: IoBuf): Int {
        check(_open) { "Channel is closed" }

        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val res = eventLoop.submitRecv(fd, ptr, buf.writableBytes.toULong(), 0)
        return when {
            res > 0 -> {
                buf.writerIndex += res
                res
            }
            else -> -1 // 0 = EOF, negative = error (e.g. -ECONNRESET)
        }
    }

    /**
     * Buffers a write by delegating to [IoUringIoTransport.write].
     *
     * The caller's [IoBuf.readerIndex] is advanced immediately so the
     * buffer can be reused or released by the caller. The actual I/O happens in [flush].
     *
     * @return number of bytes buffered.
     * @throws IllegalStateException if the channel is closed or output is shut down.
     */
    override suspend fun write(buf: IoBuf): Int {
        check(_open) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val bytes = buf.readableBytes
        if (bytes == 0) return 0
        transport.write(buf)
        return bytes
    }

    /**
     * Sends all buffered writes to the network via [IoUringIoTransport.flushSuspend].
     *
     * Delegates to the transport which selects the optimal I/O mode per connection
     * (CQE / FALLBACK_CQE / SEND_ZC) and handles partial writes and gather writes.
     *
     * @throws IllegalStateException if the channel is closed.
     */
    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        if (!transport.hasPendingWrites()) return
        transport.flushSuspend()
    }

    /**
     * Sends TCP FIN via POSIX `shutdown(SHUT_WR)`.
     *
     * Uses a direct syscall rather than `IORING_OP_SHUTDOWN` to avoid
     * the suspend/resume overhead for this non-performance-critical operation.
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
     *
     * Unflushed data is discarded; retained buffers are released without sending
     * via [IoUringIoTransport.close]. Does **not** call [flush].
     * Idempotent: subsequent calls are no-ops.
     */
    override fun close() {
        if (_open) {
            _open = false
            _active = false
            transport.close()
            close(fd)
        }
    }
}
