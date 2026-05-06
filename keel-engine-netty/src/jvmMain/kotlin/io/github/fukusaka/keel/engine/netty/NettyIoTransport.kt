package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DirectIoBuf
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.EventLoop
import io.netty.channel.socket.ChannelInputShutdownReadComplete
import io.netty.channel.socket.DuplexChannel
import io.netty.handler.ssl.SslContext
import kotlin.coroutines.resume
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Non-suspend [IoTransport] for Netty pipeline channels.
 *
 * Handles both read and write paths for Netty channels.
 *
 * **Read path**: Netty delivers data asynchronously via [channelRead]
 * before the user provides a buffer. When the inbound [ByteBuf] has a
 * single NIO backing (`nioBufferCount() == 1`), the transport wraps
 * [ByteBuf.nioBuffer] via [DirectIoBuf.wrapExternal] + a
 * [NettyByteBufOwner] — zero copy, pool pressure shifted to Netty's
 * arena. Composite buffers fall back to the allocate-and-copy path.
 * The other push-mode engine (NWConnection) still has the structural
 * constraint of `dispatch_data_t` copy; zero-copy for that engine is
 * future work.
 *
 * **auto-read**: Pipeline mode uses `autoRead = true` (Netty delivers data
 * continuously). Coroutine mode starts with `autoRead = false` and switches
 * to `autoRead = true` when [readEnabled] is set to start the read loop.
 *
 * **Write path**: batches all pending writes into Netty's outbound buffer
 * via [write][NettyNativeChannel.write], then issues a single flush on the
 * last write. The last write's [ChannelFuture] listener releases buffers
 * and invokes [onFlushComplete]. [flush] also stores the future in
 * [lastFlushFuture] for use by [awaitPendingFlush].
 *
 * **Buffer lifecycle**: `write()` retains the buffer. The flush completion
 * callback releases all buffers after Netty finishes sending.
 *
 * **Thread model**: [channelRead], [channelInactive], and [exceptionCaught]
 * run on Netty's worker [EventLoop] thread. Pipeline handlers execute
 * synchronously on the same thread. The flush completion callback runs
 * on the same EventLoop.
 *
 * [ioDispatcher] is a [NettyEventLoopDispatcher] bound to this channel's
 * [EventLoop], so coroutine-side `withContext(ioDispatcher)` hops (e.g.
 * `PipelinedChannel.read` / `write` / `flush`) resume on the same thread
 * Netty uses for inbound callbacks. This satisfies
 * [io.github.fukusaka.keel.pipeline.SuspendBridgeHandler]'s documented
 * single-thread invariant the same way the EventLoop-based Native engines
 * (epoll / kqueue / io_uring) already do.
 *
 * **awaitPendingFlush**: [flush] stores its `lastFuture` in [lastFlushFuture].
 * [awaitPendingFlush] suspends on that future so callers (e.g.
 * [io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel.awaitFlushComplete])
 * block until the write reaches the TCP send buffer. Without this, the HTTP
 * response terminator (`0\r\n\r\n`) can be queued in Netty's outbound pipeline
 * while the connection handler already moves to the next request, causing
 * truncated SSE / chunked responses under concurrent load.
 *
 * @param nettyChannel The underlying Netty channel.
 * @param allocator    Buffer allocator for read operations.
 */
internal class NettyIoTransport(
    internal val nettyChannel: NettyNativeChannel,
    allocator: BufferAllocator,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher = NettyEventLoopDispatcher(nettyChannel.eventLoop())

    // --- Read path ---

    // Guarded by EventLoop thread — both channelInactive and userEventTriggered
    // run there, so a plain var suffices.
    private var readClosedFired = false

    private fun fireReadClosed() {
        if (!readClosedFired) {
            readClosedFired = true
            onReadClosed?.invoke()
        }
    }

    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value && opened) armRead()
        }

    /**
     * Enables auto-read on the Netty channel to start the read loop.
     *
     * Once enabled, Netty continuously delivers data via [channelRead]
     * callbacks in the [handler].
     */
    private fun armRead() {
        if (!nettyChannel.isOpen) return
        nettyChannel.config().isAutoRead = true
        // Trigger initial read in case data arrived before auto-read was enabled.
        nettyChannel.read()
    }

    /**
     * Netty [ChannelInboundHandlerAdapter] that bridges push events to
     * the keel [IoTransport] callbacks.
     *
     * **Zero-copy path**: when the inbound [ByteBuf] is backed by a
     * single NIO [ByteBuffer] (`nioBufferCount() == 1`), the transport
     * wraps [ByteBuf.nioBuffer] via [DirectIoBuf.wrapExternal] and
     * installs a [NettyByteBufOwner]. Ownership of the Netty [ByteBuf]
     * is transferred to the wrapping `IoBuf`; the pooled buffer is
     * returned to Netty's arena when the keel pipeline releases the
     * `IoBuf`. No memory copy occurs.
     *
     * **Copy fallback**: composite buffers (`nioBufferCount() > 1`) fall
     * back to allocating a keel [DirectIoBuf] and copying into it via
     * [ByteBuf.getBytes]. The allocation size is rounded up to
     * [POOL_FRIENDLY_CAPACITY] so the keel [PooledDirectAllocator]
     * freelist can serve it.
     */
    internal val handler = object : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val byteBuf = msg as ByteBuf
            if (!byteBuf.isReadable) {
                byteBuf.release()
                return
            }

            val readable = byteBuf.readableBytes()
            if (byteBuf.nioBufferCount() == 1) {
                // Zero-copy wrap: ownership of byteBuf transfers to the owner.
                val nio = byteBuf.nioBuffer(byteBuf.readerIndex(), readable)
                val buf = DirectIoBuf.wrapExternal(
                    buffer = nio,
                    bytesWritten = readable,
                    memoryOwner = NettyByteBufOwner(byteBuf),
                )
                try {
                    onRead?.invoke(buf)
                } catch (t: Throwable) {
                    // onRead did not take ownership (threw before release);
                    // ensure the Netty ref is not leaked.
                    buf.release()
                    throw t
                }
            } else {
                try {
                    val cap = maxOf(readable, POOL_FRIENDLY_CAPACITY)
                    val buf = allocator.allocate(cap)
                    val bb = buf.unsafeBuffer
                    bb.position(buf.writerIndex)
                    bb.limit(buf.writerIndex + readable)
                    byteBuf.getBytes(byteBuf.readerIndex(), bb)
                    buf.writerIndex += readable
                    onRead?.invoke(buf)
                } finally {
                    byteBuf.release()
                }
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            fireReadClosed()
        }

        /**
         * Fires read-closed when the inbound half of a half-closed connection
         * has been fully drained. With [ChannelOption.ALLOW_HALF_CLOSURE] = true
         * on server channels, the peer's TCP FIN triggers
         * [ChannelInputShutdownReadComplete] (after all buffered data has been
         * delivered via [channelRead]) instead of [channelInactive], so body
         * bytes are not lost before the bridge pump can consume them.
         */
        override fun userEventTriggered(ctx: ChannelHandlerContext, evt: Any) {
            if (evt is ChannelInputShutdownReadComplete) fireReadClosed()
            ctx.fireUserEventTriggered(evt)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            fireReadClosed()
            ctx.close()
        }
    }

    // --- Netty SslHandler integration ---

    /**
     * Installs Netty's [SslHandler][io.netty.handler.ssl.SslHandler] in the
     * Netty pipeline before the keel handler.
     *
     * Decryption happens at the Netty transport level — the keel pipeline
     * receives plaintext. No keel TlsHandler is needed.
     *
     * Must be called before [readEnabled] = true to ensure the SslHandler
     * processes the TLS handshake before data delivery.
     */
    fun installSslHandler(sslContext: SslContext) {
        check(opened) { "Transport is closed" }
        val engine = sslContext.newEngine(nettyChannel.alloc())
        nettyChannel.pipeline().addFirst("ssl", io.netty.handler.ssl.SslHandler(engine))
    }

    // --- Lifecycle ---

    private var outputShutdown = false

    /**
     * Sends TCP FIN to the peer via Netty's [DuplexChannel.shutdownOutput].
     * Fire-and-forget: no blocking or suspend needed.
     */
    override fun shutdownOutput() {
        if (!outputShutdown && opened) {
            outputShutdown = true
            if (nettyChannel is DuplexChannel) {
                nettyChannel.shutdownOutput()
            }
        }
    }

    // --- Write path ---

    /**
     * The [ChannelFuture] returned by the most recent [writeAndFlush] call.
     * Set on every [flush] invocation; read by [awaitPendingFlush].
     * Only accessed on the EventLoop thread.
     */
    private var lastFlushFuture: ChannelFuture? = null

    /**
     * Sends all pending writes via Netty's [writeAndFlush].
     *
     * Batches all pending writes into Netty's outbound buffer using
     * [write][NettyNativeChannel.write], then issues a single flush on
     * the last write. The last write's [ChannelFuture] listener releases
     * buffers and invokes [onFlushComplete].
     *
     * @return always `false` because Netty writes are asynchronous.
     */
    override fun flush(): Boolean {
        val size = pendingWrites.size
        if (size == 0) return true

        // Transfer ownership for release in callback.
        val writes = ArrayList(pendingWrites)
        pendingWrites.clear()
        val totalBytes = writes.sumOf { it.length }

        val callback = onFlushComplete
        try {
            var lastFuture: ChannelFuture? = null
            for ((i, pw) in writes.withIndex()) {
                val buf = pw.buf
                val nettyBuf: ByteBuf = if (buf is NettyByteBufIoBuf) {
                    // Zero-wrap path: keel IoBuf is directly a Netty ByteBuf.
                    // Hand the underlying ByteBuf to Netty with retained slice
                    // so the flush listener's buf.release() still matches one
                    // retain (ByteBuf refCnt dropped by Netty after send).
                    buf.byteBuf.retainedSlice(pw.offset, pw.length)
                } else {
                    val bb = buf.unsafeBuffer.duplicate()
                    bb.position(pw.offset)
                    bb.limit(pw.offset + pw.length)
                    Unpooled.wrappedBuffer(bb)
                }
                if (i == size - 1) {
                    lastFuture = nettyChannel.writeAndFlush(nettyBuf)
                } else {
                    nettyChannel.write(nettyBuf)
                }
            }

            if (lastFuture != null) {
                lastFlushFuture = lastFuture
                lastFuture.addListener {
                    for (pw in writes) pw.buf.release()
                    updatePendingBytes(-totalBytes)
                    callback?.invoke()
                }
            } else {
                for (pw in writes) pw.buf.release()
                updatePendingBytes(-totalBytes)
            }
        } catch (e: Exception) {
            // Release all buffers on write failure (e.g. channel already closed).
            for (pw in writes) pw.buf.release()
            updatePendingBytes(-totalBytes)
            throw e
        }

        return false // Always async.
    }

    /**
     * Suspends until the [ChannelFuture] from the most recent [writeAndFlush]
     * completes, ensuring the written bytes have been handed off to the OS TCP
     * send buffer. Returns immediately if no flush has been issued yet or the
     * future is already done.
     *
     * Called by [io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel.awaitFlushComplete]
     * to give streaming response senders (e.g. SSE, chunked transfer) a way to
     * confirm the final `0\r\n\r\n` terminator has left the Netty pipeline
     * before the connection is reused for the next request.
     */
    override suspend fun awaitPendingFlush() {
        val future = lastFlushFuture ?: return
        if (future.isDone) return
        suspendCancellableCoroutine { cont ->
            future.addListener { cont.resume(Unit) }
        }
    }

    /**
     * Releases all pending write buffers and closes the Netty channel.
     * Unsent data is discarded. Idempotent and thread-safe.
     *
     * A non-EventLoop caller dispatches the teardown onto the Netty
     * channel's EventLoop so the `pendingWrites` / `pendingBytes`
     * mutations stay serialised with the read / write / flush paths.
     * Concurrent callers may both enqueue a teardown; the re-check
     * inside [teardownOnEventLoop] keeps the cleanup idempotent.
     */
    override fun close() {
        if (!markClosing()) return
        val loop = nettyChannel.eventLoop()
        if (loop.inEventLoop()) {
            teardownOnEventLoop()
        } else {
            loop.execute { teardownOnEventLoop() }
        }
    }

    private fun teardownOnEventLoop() {
        if (!markTeardownStarted()) return
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        // Async close — do not call sync() to avoid EventLoop deadlock.
        nettyChannel.close()
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
        /**
         * Preferred minimum capacity for inbound read buffers, chosen to
         * match [io.github.fukusaka.keel.buf.PooledDirectAllocator]'s default
         * freelist slot size (8 KiB).
         *
         * Netty delivers inbound data in packets whose size tracks the TCP
         * segment / TLS record boundary and is typically smaller than the
         * pool slot. Requesting less than the slot size forces the allocator
         * to skip the freelist and allocate a fresh `DirectByteBuffer` on
         * every read, producing a `Cleaner` + `Deallocator` pair per packet.
         * Rounding the request up to this size lets small packets hit the
         * freelist; larger packets still bypass the pool as before.
         *
         * This is a hint, not a contract: if the allocator later exposes a
         * different preferred capacity (for example, a size-class pool),
         * this constant can be adjusted independently of the allocator
         * implementation.
         */
        private const val POOL_FRIENDLY_CAPACITY = 8192
    }
}
