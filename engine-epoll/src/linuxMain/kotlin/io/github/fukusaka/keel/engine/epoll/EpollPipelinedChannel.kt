package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.close
import platform.posix.errno
import platform.posix.read

/**
 * Pipeline channel for epoll-based I/O on Linux.
 *
 * Reads are driven by EPOLLIN callbacks from the [eventLoop]. Each callback
 * allocates a buffer, performs a POSIX `read()`, and feeds the data into the
 * [pipeline] via [ChannelPipeline.notifyRead].
 *
 * Same architecture as [KqueuePipelinedChannel][io.github.fukusaka.keel.engine.kqueue.KqueuePipelinedChannel]
 * but using epoll instead of kqueue for event notification.
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollPipelinedChannel(
    private val fd: Int,
    private val transport: EpollIoTransport,
    private val eventLoop: EpollEventLoop,
    override val allocator: BufferAllocator,
    logger: Logger,
) : PipelinedChannel {

    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val isActive: Boolean get() = !closed
    override val isWritable: Boolean get() = true

    @kotlin.concurrent.Volatile
    private var closed = false

    /** Registers EPOLLIN callback to start the read loop. Must be called on EventLoop thread. */
    fun armRead() {
        if (closed) return
        eventLoop.registerCallback(fd, EpollEventLoop.Interest.READ) {
            onReadable()
        }
    }

    private fun onReadable() {
        if (closed) return
        val buf = allocator.allocate(READ_BUFFER_SIZE)
        val ptr = (buf.unsafePointer + buf.writerIndex)!!
        val n = read(fd, ptr, buf.writableBytes.convert())
        when {
            n > 0 -> {
                buf.writerIndex += n.toInt()
                pipeline.notifyRead(buf)
                armRead()
            }
            n == 0L -> {
                buf.release()
                pipeline.notifyInactive()
                close()
            }
            else -> {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    buf.release()
                    armRead()
                } else {
                    buf.release()
                    pipeline.notifyInactive()
                    close()
                }
            }
        }
    }

    /**
     * Closes this channel by delegating to [EpollIoTransport.close].
     * Releases pending write buffers and closes the socket fd. Idempotent.
     */
    override fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    private companion object {
        private const val READ_BUFFER_SIZE = 8192
    }
}
