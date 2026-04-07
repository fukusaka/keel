package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel.Companion.SUSPEND_BRIDGE_NAME
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.coroutines.CoroutineDispatcher
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.SHUT_WR
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.shutdown

/**
 * Unified kqueue channel supporting both Pipeline mode and Channel mode.
 *
 * **Pipeline mode** (push, zero-suspend): engine feeds data into the pipeline
 * via [armRead] → [ChannelPipeline.notifyRead]. Handlers process data
 * synchronously. Used by [KqueueEngine.bindPipeline].
 *
 * **Channel mode** (pull, suspend): a [SuspendBridgeHandler] is installed
 * at the end of the pipeline. App calls suspend [read]/[write]/[flush].
 * Used by [KqueueEngine.bind] and [KqueueEngine.connect].
 *
 * ```
 * Pipeline mode:  HEAD ↔ handlers ↔ TAIL
 * Channel mode:   HEAD ↔ handlers ↔ SuspendBridgeHandler ↔ TAIL
 *                                         ↑
 *                                   App: read() / write() / flush()
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class KqueuePipelinedChannel(
    private val fd: Int,
    private val transport: KqueueIoTransport,
    private val eventLoop: KqueueEventLoop,
    override val allocator: BufferAllocator,
    private val logger: Logger,
    override val remoteAddress: SocketAddress? = null,
    override val localAddress: SocketAddress? = null,
) : PipelinedChannel {

    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val isActive: Boolean get() = !closed
    override val isOpen: Boolean get() = !closed
    override val isWritable: Boolean get() = true
    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop
    override val supportsDeferredFlush: Boolean get() = true

    @kotlin.concurrent.Volatile
    private var closed = false

    // --- Channel mode: bridge lifecycle ---

    private var bridge: SuspendBridgeHandler? = null
    private var readArmed = false

    override fun ensureBridge(): SuspendBridgeHandler {
        bridge?.let { return it }
        val handler = SuspendBridgeHandler()
        pipeline.addLast(SUSPEND_BRIDGE_NAME, handler)
        bridge = handler
        if (!readArmed) {
            readArmed = true
            armRead()
        }
        return handler
    }

    override suspend fun awaitFlushComplete() {
        check(!closed) { "Channel is closed" }
        transport.awaitPendingFlush()
    }

    override suspend fun awaitClosed() {}

    private var outputShutdown = false

    /**
     * Sends TCP FIN to the peer via POSIX `shutdown(fd, SHUT_WR)`.
     *
     * The read side remains open so the peer's remaining data can be consumed.
     * Idempotent — safe to call multiple times.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && !closed) {
            outputShutdown = true
            shutdown(fd, SHUT_WR)
        }
    }

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
    override fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    private companion object {
        /** Default read buffer size (8 KiB). */
        private const val READ_BUFFER_SIZE = 8192
    }
}
