package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel.Companion.SUSPEND_BRIDGE_NAME
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler

/**
 * Unified Node.js channel supporting both Pipeline mode and Channel mode.
 *
 * **Pipeline mode** (push, zero-suspend): Node.js `socket.on("data")` callback
 * copies Buffer data into [IoBuf] and feeds it into the pipeline via
 * [ChannelPipeline.notifyRead]. Handlers process data synchronously.
 * Used by [NodeEngine.bindPipeline].
 *
 * **Channel mode** (pull, suspend): a [SuspendBridgeHandler] is installed
 * at the end of the pipeline. App calls suspend [read]/[write]/[flush].
 * Used by [NodeEngine.bind] and ktor-engine's handleConnection.
 *
 * ```
 * Pipeline mode:  HEAD <-> handlers <-> TAIL
 * Channel mode:   HEAD <-> handlers <-> SuspendBridgeHandler <-> TAIL
 *                                              |
 *                                        App: read() / write() / flush()
 * ```
 *
 * **Read path (copy from Node.js Buffer)**: Node.js delivers data
 * asynchronously via `socket.on("data")` before the user provides a buffer.
 * The Buffer content is copied byte-by-byte into [IoBuf]. This is an
 * accepted limitation — same structural constraint as Netty's ByteBuf
 * and NWConnection's dispatch_data_t copy.
 *
 * **Thread model**: JS is single-threaded (Node.js event loop). All state
 * fields are accessed from the same thread — no locking or @Volatile needed.
 *
 * @param socket    The Node.js net.Socket.
 * @param allocator Buffer allocator for read operations.
 * @param remoteAddress Remote peer address.
 * @param localAddress  Local bind address.
 * @param logger    Logger instance.
 */
internal class NodePipelinedChannel(
    private val socket: Socket,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
    private val logger: Logger,
) : PipelinedChannel {

    private val transport = NodeIoTransport(socket)
    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)

    private var closed = false

    override val isActive: Boolean get() = !closed
    override val isOpen: Boolean get() = !closed
    override val isWritable: Boolean get() = !closed && transport.isWritable
    override val supportsDeferredFlush: Boolean get() = false

    init {
        transport.onWritabilityChanged = { writable ->
            pipeline.notifyWritabilityChanged(writable)
        }
    }

    // --- Channel mode: bridge lifecycle ---

    private var bridge: SuspendBridgeHandler? = null
    private var readArmed = false

    /**
     * Lazily installs [SuspendBridgeHandler] and arms the read loop.
     *
     * First call: creates the bridge handler, adds it to the pipeline,
     * and registers `socket.on("data")` to start the read loop.
     * Subsequent calls return the existing handler.
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

    override suspend fun awaitFlushComplete() {}

    private var outputShutdown = false

    /**
     * Sends TCP FIN to the peer via Node.js `socket.end()`.
     * Fire-and-forget: no blocking or suspend needed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && !closed) {
            outputShutdown = true
            socket.end()
        }
    }

    // --- Pipeline mode: socket.on("data") -> notifyRead ---

    /**
     * Registers `socket.on("data")` and `socket.on("end"/"error")` to
     * feed data into the pipeline.
     *
     * Must be called after pipeline initialization. Each "data" event
     * copies the Node.js Buffer into [IoBuf] and calls [pipeline.notifyRead].
     */
    fun armRead() {
        socket.on("data") { data: dynamic ->
            if (closed) return@on
            val dataLength = data.length as Int
            if (dataLength == 0) return@on

            val buf = allocator.allocate(dataLength)
            // Copy Node.js Buffer to IoBuf via bulk copy.
            // Node.js Buffer is a Uint8Array subclass; extract bytes into
            // a ByteArray and use writeByteArray() for efficient transfer.
            val bytes = ByteArray(dataLength)
            for (i in 0 until dataLength) {
                bytes[i] = (data[i] as Int).toByte()
            }
            buf.writeByteArray(bytes, 0, dataLength)

            pipeline.notifyRead(buf)
        }

        socket.on("end") { _: dynamic ->
            if (!closed) {
                pipeline.notifyInactive()
            }
        }

        socket.on("error") { _: dynamic ->
            if (!closed) {
                pipeline.notifyInactive()
            }
        }
    }

    /**
     * Closes the Node.js socket and releases transport resources.
     * Idempotent: subsequent calls are no-ops.
     */
    override fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    /**
     * Returns immediately — Node.js socket.destroy() is synchronous.
     */
    override suspend fun awaitClosed() {}
}
