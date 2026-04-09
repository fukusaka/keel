package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.ChannelPipeline
import io.github.fukusaka.keel.pipeline.DefaultChannelPipeline
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel.Companion.SUSPEND_BRIDGE_NAME
import io.github.fukusaka.keel.pipeline.SuspendBridgeHandler
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DuplexChannel
import io.netty.handler.ssl.SslContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Unified Netty channel supporting both Pipeline mode and Channel mode.
 *
 * **Pipeline mode** (push, zero-suspend): Netty's [channelRead] callback
 * copies [ByteBuf] data into [IoBuf] and feeds it into the pipeline via
 * [ChannelPipeline.notifyRead]. Handlers process data synchronously.
 * Used by [NettyEngine.bindPipeline].
 *
 * **Channel mode** (pull, suspend): a [SuspendBridgeHandler] is installed
 * at the end of the pipeline. App calls suspend [read]/[write]/[flush].
 * Used by [NettyEngine.bind] and ktor-engine's handleConnection.
 *
 * ```
 * Pipeline mode:  HEAD ↔ handlers ↔ TAIL
 * Channel mode:   HEAD ↔ handlers ↔ SuspendBridgeHandler ↔ TAIL
 *                                         ↑
 *                                   App: read() / write() / flush()
 * ```
 *
 * **Read path (copy from ByteBuf)**: Unlike kqueue/epoll/NIO which read
 * directly into [IoBuf], Netty delivers data asynchronously via
 * [channelRead] before the user provides a buffer. The [ByteBuf] content
 * is copied into [IoBuf] via [ByteBuf.getBytes]. This is an accepted
 * limitation — same structural constraint as NWConnection's
 * `dispatch_data_t` copy. Zero-copy push model support is future work.
 *
 * **auto-read**: Pipeline mode uses `autoRead = true` (Netty delivers data
 * continuously). Channel mode starts with `autoRead = false` and switches
 * to `autoRead = true` when [ensureBridge] is called to start the read loop.
 *
 * **Thread model**: [channelRead], [channelInactive], and [exceptionCaught]
 * run on Netty's worker EventLoop thread. Pipeline handlers execute
 * synchronously on the same thread. Channel mode operations ([read]/[write])
 * use [withContext(coroutineDispatcher)][PipelinedChannel.read] for
 * EventLoop thread affinity.
 *
 * @param nettyChannel The underlying Netty channel.
 * @param allocator    Buffer allocator for read operations.
 */
class NettyPipelinedChannel internal constructor(
    private val nettyChannel: NettyNativeChannel,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
    logger: Logger,
) : PipelinedChannel {

    private val transport = NettyIoTransport(nettyChannel)
    override val pipeline: ChannelPipeline = DefaultChannelPipeline(this, transport, logger)

    // Local closed flag for synchronous lifecycle checks. Netty's close()
    // is async, so nettyChannel.isOpen may still return true after close().
    @Volatile
    private var closed = false

    override val isActive: Boolean get() = !closed && nettyChannel.isActive
    override val isOpen: Boolean get() = !closed
    override val isWritable: Boolean get() = !closed && nettyChannel.isWritable && transport.isWritable

    // Netty flush is always async (ChannelFuture). BufferedSuspendSink must
    // NOT reuse the same buffer after flush — the ChannelFuture listener
    // releases it asynchronously on the Netty EventLoop thread.
    override val supportsDeferredFlush: Boolean get() = true

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
     * and enables `autoRead = true` to start the Netty read loop.
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
     * Sends TCP FIN to the peer via Netty's [DuplexChannel.shutdownOutput].
     * Fire-and-forget: no blocking or suspend needed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && !closed) {
            outputShutdown = true
            if (nettyChannel is DuplexChannel) {
                nettyChannel.shutdownOutput()
            }
        }
    }

    // --- Pipeline mode: Netty channelRead → notifyRead ---

    /**
     * Enables auto-read on the Netty channel to start the read loop.
     *
     * Called when [ensureBridge] installs the bridge (Channel mode) or
     * when the pipeline is set up for Pipeline mode. Once enabled, Netty
     * continuously delivers data via [channelRead] callbacks.
     */
    fun armRead() {
        if (!nettyChannel.isOpen) return
        nettyChannel.config().isAutoRead = true
        // Trigger initial read in case data arrived before auto-read was enabled.
        nettyChannel.read()
    }

    /**
     * Netty [ChannelInboundHandlerAdapter] that bridges push events to
     * the keel [ChannelPipeline].
     *
     * [channelRead] copies [ByteBuf] data into [IoBuf] and feeds it into
     * the pipeline via [notifyRead]. The copy is unavoidable because Netty
     * delivers data before the user provides a destination buffer.
     */
    internal val handler = object : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val byteBuf = msg as ByteBuf
            try {
                if (!byteBuf.isReadable) return

                val readable = byteBuf.readableBytes()
                val buf = allocator.allocate(readable)
                val bb = buf.unsafeBuffer
                bb.position(buf.writerIndex)
                bb.limit(buf.writerIndex + readable)
                byteBuf.getBytes(byteBuf.readerIndex(), bb)
                buf.writerIndex += readable

                pipeline.notifyRead(buf)
            } finally {
                byteBuf.release()
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            pipeline.notifyInactive()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            pipeline.notifyError(cause)
            ctx.close()
        }
    }

    // --- Netty SslHandler integration ---

    /**
     * Installs Netty's [SslHandler][io.netty.handler.ssl.SslHandler] in the
     * Netty pipeline before the keel handler.
     *
     * Decryption happens at the Netty transport level — the keel pipeline
     * receives plaintext. No keel [TlsHandler] is needed.
     *
     * Must be called before [armRead] / [ensureBridge] to ensure the
     * SslHandler processes the TLS handshake before data delivery.
     */
    fun installSslHandler(sslContext: SslContext) {
        check(!closed) { "Channel is closed" }
        val engine = sslContext.newEngine(nettyChannel.alloc())
        nettyChannel.pipeline().addFirst("ssl", io.netty.handler.ssl.SslHandler(engine))
    }

    /**
     * Closes the Netty channel and releases transport resources.
     * Idempotent: subsequent calls are no-ops.
     */
    override fun close() {
        if (closed) return
        closed = true
        transport.close()
    }

    /**
     * Suspends until the Netty channel is fully closed.
     * Returns immediately if already closed.
     */
    override suspend fun awaitClosed() {
        if (!nettyChannel.isOpen) return
        suspendCancellableCoroutine { cont ->
            nettyChannel.closeFuture().addListener {
                cont.resume(Unit)
            }
        }
    }

    companion object {
        /** Extracts [SocketAddress] from a Java NIO [InetSocketAddress]. */
        internal fun toSocketAddress(addr: java.net.SocketAddress?): SocketAddress? {
            val inet = addr as? InetSocketAddress ?: return null
            return SocketAddress(inet.address.hostAddress, inet.port)
        }
    }
}
