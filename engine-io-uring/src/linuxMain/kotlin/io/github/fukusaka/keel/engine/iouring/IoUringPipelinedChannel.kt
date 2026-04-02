package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io_uring.keel_cqe_get_buf_id
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.ENOBUFS

/**
 * io_uring-based [PipelinedChannel] for zero-suspend protocol processing.
 *
 * Connects a [ChannelPipeline] to the io_uring EventLoop via multishot recv
 * and provided buffer ring. Data received from the network is delivered
 * directly to the pipeline as [pipeline.notifyRead] — no coroutine
 * suspend/resume on the hot path.
 *
 * **Read path (inbound)**:
 * ```
 * io_uring CQE (multishot recv)
 *   → reuse pre-allocated RingBufferIoBuf wrapper (zero allocation)
 *   → pipeline.notifyRead(buf)
 *   → handler chain processes data synchronously on EventLoop thread
 *   → handler calls buf.release() → returnBuffer to kernel ring
 * ```
 *
 * **Write path (outbound)**:
 * ```
 * handler calls ctx.propagateWriteAndFlush(response)
 *   → HeadHandler.onWrite/onFlush → IoUringIoTransport
 *   → direct send() + EAGAIN → SEND SQE (fire-and-forget)
 * ```
 *
 * **Buffer management**: pre-allocated [RingBufferIoBuf] wrappers (one per
 * buffer slot) are reused via [RingBufferIoBuf.reset] on each CQE. When the
 * handler releases the buffer, it is returned to the provided buffer ring
 * for kernel reuse.
 *
 * **ENOBUFS handling**: when all provided buffers are consumed, the kernel
 * terminates the multishot recv with -ENOBUFS. The channel re-arms
 * immediately; TCP flow control prevents data loss.
 *
 * @param fd         The connected socket file descriptor.
 * @param transport  Shared [IoUringIoTransport] for write/flush.
 * @param eventLoop  The owning [IoUringEventLoop].
 * @param bufferRing The [ProvidedBufferRing] for multishot recv.
 * @param allocator  Buffer allocator for this channel.
 * @param logger     Logger for pipeline error reporting.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IoUringPipelinedChannel(
    private val fd: Int,
    private val transport: IoUringIoTransport,
    private val eventLoop: IoUringEventLoop,
    private val bufferRing: ProvidedBufferRing,
    override val allocator: BufferAllocator,
    logger: Logger,
) : PipelinedChannel {

    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val isWritable: Boolean get() = true // backpressure: future enhancement

    private var closed = false
    override val isActive: Boolean get() = !closed

    // Pre-allocated IoBuf wrappers: one per buffer slot.
    // Reused on each CQE callback via reset() — zero allocation on hot path.
    private val wrappers = Array(bufferRing.bufferCount) { bufId ->
        RingBufferIoBuf(bufId, bufferRing) { buf ->
            // Called when handler releases the buffer (refCount → 0).
            bufferRing.returnBuffer(bufId)
        }
    }

    private var multishotSlot = -1

    /**
     * Arms multishot recv and notifies the pipeline that the channel is active.
     *
     * Must be called on the EventLoop thread after the pipeline is initialized
     * (handlers added via [ChannelPipeline.addLast] etc.).
     */
    fun armRecv() {
        multishotSlot = eventLoop.submitMultishotRecv(
            fd = fd,
            bgid = bufferRing.bgid,
            onCqe = { res, flags ->
                if (closed) return@submitMultishotRecv
                when {
                    res > 0 -> {
                        val bufId = keel_cqe_get_buf_id(flags).toInt()
                        val buf = wrappers[bufId]
                        buf.reset()
                        buf.writerIndex = res
                        pipeline.notifyRead(buf)
                    }
                    res == -ENOBUFS -> {
                        // All provided buffers consumed; kernel terminated multishot.
                        // Re-arm immediately; TCP flow control holds incoming data.
                        armRecv()
                    }
                    else -> {
                        // EOF (0) or error: close connection.
                        pipeline.notifyInactive()
                        close()
                    }
                }
            },
        )
        pipeline.notifyActive()
    }

    /**
     * Closes the connection and releases resources.
     *
     * Cancels the multishot recv, releases pending write buffers,
     * and closes the socket fd. Idempotent.
     */
    fun close() {
        if (!closed) {
            closed = true
            if (multishotSlot >= 0) {
                eventLoop.cancelMultishot(multishotSlot)
                multishotSlot = -1
            }
            transport.close()
            platform.posix.close(fd)
        }
    }
}
