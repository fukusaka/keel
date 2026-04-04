package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.cinterop.plus
import kotlinx.coroutines.CoroutineDispatcher
import platform.posix.EAGAIN
import platform.posix.EWOULDBLOCK
import platform.posix.close
import platform.posix.errno
import platform.posix.read

/**
 * Unified epoll channel supporting both Pipeline mode and Channel mode.
 *
 * **Pipeline mode** (push, zero-suspend): engine feeds data into the pipeline
 * via [armRead] → [ChannelPipeline.notifyRead]. Handlers process data
 * synchronously. Used by [EpollEngine.bindPipeline].
 *
 * **Channel mode** (pull, suspend): a [SuspendBridgeHandler] is installed
 * at the end of the pipeline. App calls suspend [read]/[write]/[flush].
 * Used by [EpollEngine.bind] and [EpollEngine.connect].
 *
 * ```
 * Pipeline mode:  HEAD ↔ handlers ↔ TAIL
 * Channel mode:   HEAD ↔ handlers ↔ SuspendBridgeHandler ↔ TAIL
 *                                         ↑
 *                                   App: read() / write() / flush()
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
internal class EpollPipelinedChannel(
    private val fd: Int,
    private val transport: EpollIoTransport,
    private val eventLoop: EpollEventLoop,
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

    // Lazily installed when Channel mode is first used (read/write/flush).
    private var bridge: SuspendBridgeHandler? = null
    private var readArmed = false

    /**
     * Installs [SuspendBridgeHandler] and arms the read loop if not already done.
     * Called on first suspend read/write/flush.
     */
    private fun ensureBridge(): SuspendBridgeHandler {
        bridge?.let { return it }
        val handler = SuspendBridgeHandler()
        pipeline.addLast("__suspend_bridge__", handler)
        bridge = handler
        if (!readArmed) {
            readArmed = true
            armRead()
        }
        return handler
    }

    // --- Channel mode: suspend API ---

    /**
     * Reads decrypted/decoded data via [SuspendBridgeHandler].
     *
     * On first call, installs [SuspendBridgeHandler] into the pipeline
     * and starts the read loop ([armRead]). Suspends until data arrives
     * from the pipeline's inbound path.
     *
     * @return number of bytes read, or -1 on EOF.
     * @throws IllegalStateException if the channel is closed.
     */
    override suspend fun read(buf: IoBuf): Int {
        check(!closed) { "Channel is closed" }
        return ensureBridge().read(buf)
    }

    /**
     * Writes [buf] through the pipeline's outbound path via [SuspendBridgeHandler].
     *
     * Delegates to [io.github.fukusaka.keel.pipeline.ChannelHandlerContext.propagateWrite]
     * which traverses outbound handlers before reaching
     * [io.github.fukusaka.keel.pipeline.HeadHandler] → [EpollIoTransport].
     *
     * @return number of bytes buffered (actual send happens on [flush]).
     * @throws IllegalStateException if the channel is closed or output is shut down.
     */
    override suspend fun write(buf: IoBuf): Int {
        check(!closed) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val n = buf.readableBytes
        if (n == 0) return 0
        ensureBridge().write(buf)
        return n
    }

    /**
     * Flushes buffered writes through the pipeline's outbound path.
     *
     * Delegates to [SuspendBridgeHandler.flush] → [EpollIoTransport.flush].
     * Fire-and-forget: if EAGAIN, the transport registers EPOLLOUT callback
     * and retries asynchronously.
     *
     * @throws IllegalStateException if the channel is closed.
     */
    override suspend fun flush() {
        check(!closed) { "Channel is closed" }
        ensureBridge().flush()
    }

    /** No-op. EOF is detected via [read] returning -1. */
    override suspend fun awaitClosed() {}

    private var outputShutdown = false

    /**
     * Sends TCP FIN to the peer via POSIX `shutdown(fd, SHUT_WR)`.
     * Idempotent — safe to call multiple times.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && !closed) {
            outputShutdown = true
            platform.posix.shutdown(fd, platform.posix.SHUT_WR)
        }
    }

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
        eventLoop.cleanupFd(fd)
        transport.close()
    }

    private companion object {
        /** Default read buffer size (8 KiB). */
        private const val READ_BUFFER_SIZE = 8192
    }
}
