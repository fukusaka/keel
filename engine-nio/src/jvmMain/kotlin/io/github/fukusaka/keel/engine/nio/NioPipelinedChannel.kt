package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel.Companion.SUSPEND_BRIDGE_NAME
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import java.net.InetSocketAddress
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

/**
 * Unified NIO channel supporting both Pipeline mode and Channel mode.
 *
 * **Pipeline mode** (push, zero-suspend): engine feeds data into the pipeline
 * via [armRead] → [ChannelPipeline.notifyRead]. Handlers process data
 * synchronously. Used by [NioEngine.bindPipeline].
 *
 * **Channel mode** (pull, suspend): a [SuspendBridgeHandler] is installed
 * at the end of the pipeline. App calls suspend [read]/[write]/[flush].
 * Used by [NioEngine.bind] and [NioEngine.connect].
 *
 * ```
 * Pipeline mode:  HEAD ↔ handlers ↔ TAIL
 * Channel mode:   HEAD ↔ handlers ↔ SuspendBridgeHandler ↔ TAIL
 *                                         ↑
 *                                   App: read() / write() / flush()
 * ```
 *
 * **Thread model**: all callbacks execute on the [eventLoop] thread.
 */
internal class NioPipelinedChannel(
    private val socketChannel: SocketChannel,
    private val selectionKey: SelectionKey,
    private val transport: NioIoTransport,
    private val eventLoop: NioEventLoop,
    override val allocator: BufferAllocator,
    private val logger: Logger,
    override val remoteAddress: SocketAddress? = null,
    override val localAddress: SocketAddress? = null,
) : PipelinedChannel {

    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val isActive: Boolean get() = socketChannel.isOpen
    override val isOpen: Boolean get() = socketChannel.isOpen
    override val isWritable: Boolean get() = true
    override val coroutineDispatcher: CoroutineDispatcher get() = eventLoop
    override val supportsDeferredFlush: Boolean get() = true

    /** ForkJoinPool work-stealing outperforms EventLoop fixed-partition for pipeline. */
    @Suppress("InjectDispatcher") // Intentional: NIO pipeline runs on Dispatchers.Default (design.md §17)
    override val appDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    // --- Channel mode: bridge lifecycle ---

    private var bridge: SuspendBridgeHandler? = null
    private var readArmed = false

    /**
     * Lazily installs [SuspendBridgeHandler] and arms the NIO read loop.
     *
     * Called on the EventLoop thread (guaranteed by [PipelinedChannel.read]'s
     * [withContext]). The [armRead] call registers OP_READ interest with the
     * Selector, which requires EventLoop thread affinity.
     */
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
        check(isOpen) { "Channel is closed" }
        transport.awaitPendingFlush()
    }

    override suspend fun awaitClosed() {}

    private var outputShutdown = false

    /**
     * Sends TCP FIN to the peer via [SocketChannel.shutdownOutput].
     * Idempotent — safe to call multiple times.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && socketChannel.isOpen) {
            outputShutdown = true
            socketChannel.shutdownOutput()
        }
    }

    // --- Pipeline mode: callback-driven read ---

    /** Registers OP_READ callback to start the read loop. Must be called on EventLoop thread. */
    fun armRead() {
        if (!socketChannel.isOpen) return
        eventLoop.setInterestCallback(
            selectionKey,
            SelectionKey.OP_READ,
            Runnable {
                onReadable()
            },
        )
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
     * Closes this channel. Cancels the SelectionKey and delegates to
     * [NioIoTransport.close] which releases pending buffers and closes
     * the SocketChannel. Idempotent.
     */
    override fun close() {
        if (socketChannel.isOpen) {
            selectionKey.cancel()
            transport.close()
        }
    }

    companion object {
        private const val READ_BUFFER_SIZE = 8192

        /** Extracts [SocketAddress] from a Java NIO [InetSocketAddress]. */
        internal fun toSocketAddress(addr: java.net.SocketAddress?): SocketAddress? {
            val inet = addr as? InetSocketAddress ?: return null
            return SocketAddress(inet.address.hostAddress, inet.port)
        }
    }
}
