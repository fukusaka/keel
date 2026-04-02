package io.github.fukusaka.keel.engine.kqueue

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
 * Pipeline channel for kqueue-based I/O on macOS.
 *
 * Reads are driven by EVFILT_READ callbacks from the [eventLoop]. Each callback
 * allocates a buffer, performs a POSIX `read()`, and feeds the data into the
 * [pipeline] via [ChannelPipeline.notifyRead]. Handlers process the data
 * synchronously on the EventLoop thread — no coroutine suspension.
 *
 * **Read loop**: unlike io_uring's multishot recv (which receives multiple CQEs
 * per SQE), kqueue requires re-registering EVFILT_READ after each callback.
 * [armRead] is called after each successful read to continue the loop.
 *
 * **Buffer lifecycle**: buffers are allocated from [allocator] per read and
 * released by the pipeline handler chain (typically after encoding the response).
 *
 * ```
 * kevent(EVFILT_READ) → read(fd, buf) → pipeline.notifyRead(buf)
 *   → decoder → routing → encoder → transport.write(resp) + flush()
 *   → POSIX write(fd) → armRead() (re-register for next read)
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueuePipelinedChannel(
    private val fd: Int,
    private val transport: KqueueIoTransport,
    private val eventLoop: KqueueEventLoop,
    override val allocator: BufferAllocator,
    logger: Logger,
) : PipelinedChannel {

    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val isActive: Boolean get() = !closed
    override val isWritable: Boolean get() = true

    @kotlin.concurrent.Volatile
    private var closed = false

    /**
     * Registers EVFILT_READ callback to start the read loop.
     *
     * Must be called on the EventLoop thread after pipeline initialization.
     * Each successful read re-arms the callback via [armRead].
     */
    fun armRead() {
        if (closed) return
        eventLoop.registerCallback(fd, KqueueEventLoop.Interest.READ) {
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
                // EOF — peer closed connection.
                buf.release()
                pipeline.notifyInactive()
                close()
            }
            else -> {
                val err = errno
                if (err == EAGAIN || err == EWOULDBLOCK) {
                    // Spurious wakeup — re-arm and wait for next event.
                    buf.release()
                    armRead()
                } else {
                    // Read error (ECONNRESET, etc.)
                    buf.release()
                    pipeline.notifyInactive()
                    close()
                }
            }
        }
    }

    /**
     * Closes this channel by delegating to [KqueueIoTransport.close].
     *
     * Releases pending write buffers and closes the socket fd.
     * Does NOT unregister pending EVFILT_READ callbacks — the closed flag
     * prevents further processing if the callback fires. Idempotent.
     */
    fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    private companion object {
        /** Read buffer size. Matches KqueueChannel's default (8 KiB). */
        private const val READ_BUFFER_SIZE = 8192
    }
}
