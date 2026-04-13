package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Base class for all engine [PipelinedChannel] implementations.
 *
 * Wires the [IoTransport] callbacks to the [ChannelPipeline] and provides
 * default implementations for all [PipelinedChannel] methods by delegating
 * to the transport. Engine subclasses only need to override engine-specific
 * members (if any).
 *
 * ```
 * Engine creates:
 *   val transport = KqueueIoTransport(fd, eventLoop, allocator)
 *   val channel = KqueuePipelinedChannel(transport, logger, remote, local)
 *
 * AbstractPipelinedChannel wires:
 *   transport.onRead        → pipeline.notifyRead(buf)
 *   transport.onReadClosed  → pipeline.notifyInactive() + close()
 *   transport.onWritabilityChanged → pipeline.notifyWritabilityChanged()
 *   ensureBridge()          → transport.readEnabled = true
 *   shutdownOutput()        → transport.shutdownOutput()
 *   close()                 → transport.close()
 * ```
 */
abstract class AbstractPipelinedChannel(
    val transport: IoTransport,
    protected val logger: Logger,
    override val remoteAddress: SocketAddress? = null,
    override val localAddress: SocketAddress? = null,
) : PipelinedChannel {

    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)
    override val allocator: BufferAllocator get() = transport.allocator
    override val isActive: Boolean get() = transport.isOpen
    override val isOpen: Boolean get() = transport.isOpen
    override val isWritable: Boolean get() = transport.isOpen && transport.isWritable
    override val ioDispatcher: CoroutineDispatcher get() = transport.ioDispatcher
    override val appDispatcher: CoroutineDispatcher get() = transport.appDispatcher
    override val supportsDeferredFlush: Boolean get() = transport.supportsDeferredFlush

    private var bridge: SuspendBridgeHandler? = null
    private var readArmed = false

    init {
        transport.onWritabilityChanged = { writable ->
            pipeline.notifyWritabilityChanged(writable)
        }
        transport.onRead = { buf ->
            pipeline.notifyRead(buf)
        }
        transport.onReadClosed = {
            pipeline.notifyInactive()
            close()
        }
    }

    override fun ensureBridge(): SuspendBridgeHandler {
        bridge?.let { return it }
        val handler = SuspendBridgeHandler()
        pipeline.addLast(PipelinedChannel.SUSPEND_BRIDGE_NAME, handler)
        bridge = handler
        if (!readArmed) {
            readArmed = true
            transport.readEnabled = true
        }
        return handler
    }

    override fun shutdownOutput() {
        transport.shutdownOutput()
    }

    override suspend fun awaitFlushComplete() {
        transport.awaitPendingFlush()
    }

    override suspend fun awaitClosed() {
        transport.awaitClosed()
    }

    override fun close() {
        transport.close()
    }
}
