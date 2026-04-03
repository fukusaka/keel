package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

/**
 * Pipeline channel for NIO-based I/O on JVM.
 *
 * Reads are driven by OP_READ callbacks from the [eventLoop]. Each callback
 * allocates a buffer, performs a [SocketChannel.read], and feeds the data
 * into the [pipeline] via [ChannelPipeline.notifyRead].
 *
 * Same architecture as kqueue/epoll pipeline channels but using JVM NIO
 * (Selector + SelectionKey) instead of POSIX syscalls.
 *
 * **Thread model**: all callbacks execute on the [eventLoop] thread.
 */
internal class NioPipelinedChannel(
    private val socketChannel: SocketChannel,
    private val selectionKey: SelectionKey,
    private val transport: NioIoTransport,
    private val eventLoop: NioEventLoop,
    override val allocator: BufferAllocator,
    logger: Logger,
) : PipelinedChannel {

    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val isActive: Boolean get() = socketChannel.isOpen
    override val isWritable: Boolean get() = true

    /** Registers OP_READ callback to start the read loop. Must be called on EventLoop thread. */
    fun armRead() {
        if (!socketChannel.isOpen) return
        eventLoop.setInterestCallback(selectionKey, SelectionKey.OP_READ, Runnable {
            onReadable()
        })
    }

    private fun onReadable() {
        if (!socketChannel.isOpen) return
        val buf = allocator.allocate(READ_BUFFER_SIZE)
        val bb = buf.unsafeBuffer
        bb.position(buf.writerIndex)
        bb.limit(buf.capacity)
        val n = socketChannel.read(bb)
        when {
            n > 0 -> {
                buf.writerIndex += n
                pipeline.notifyRead(buf)
                armRead()
            }
            n == -1 -> {
                // EOF
                buf.release()
                pipeline.notifyInactive()
                close()
            }
            else -> {
                // n == 0: no data available, re-arm.
                buf.release()
                armRead()
            }
        }
    }

    /**
     * Closes this channel by delegating to [NioIoTransport.close].
     * Releases pending write buffers and closes the socket channel. Idempotent.
     */
    override fun close() {
        transport.close()
    }

    private companion object {
        private const val READ_BUFFER_SIZE = 8192
    }
}
