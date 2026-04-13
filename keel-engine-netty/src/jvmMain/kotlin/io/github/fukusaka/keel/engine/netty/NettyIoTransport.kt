package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import io.github.fukusaka.keel.pipeline.AbstractIoTransport.PendingWrite
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DuplexChannel
import io.netty.handler.ssl.SslContext
import kotlin.coroutines.resume
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Non-suspend [IoTransport] for Netty pipeline channels.
 *
 * Handles both read and write paths for Netty channels.
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
 * to `autoRead = true` when [readEnabled] is set to start the read loop.
 *
 * **Write path**: batches all pending writes into Netty's outbound buffer
 * via [write][NettyNativeChannel.write], then issues a single flush on the
 * last write. The last write's [ChannelFuture] listener releases buffers
 * and invokes [onFlushComplete].
 *
 * **Buffer lifecycle**: `write()` retains the buffer. The flush completion
 * callback releases all buffers after Netty finishes sending.
 *
 * **Thread model**: [channelRead], [channelInactive], and [exceptionCaught]
 * run on Netty's worker EventLoop thread. Pipeline handlers execute
 * synchronously on the same thread. The flush completion callback runs
 * on Netty's EventLoop thread.
 *
 * **No awaitPendingFlush**: Netty internally buffers data submitted via
 * `writeAndFlush` and processes it in EventLoop order. Even if the channel
 * is closed immediately after flush, Netty guarantees the write is processed
 * before close (same EventLoop ordering). This is the same model as
 * NWConnection (framework-managed flow control).
 *
 * @param nettyChannel The underlying Netty channel.
 * @param allocator    Buffer allocator for read operations.
 */
internal class NettyIoTransport(
    private val nettyChannel: NettyNativeChannel,
    allocator: BufferAllocator,
) : AbstractIoTransport(allocator) {

    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    // --- Read path ---

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
     * [channelRead] copies [ByteBuf] data into [IoBuf] and delivers it
     * via [onRead]. The copy is unavoidable because Netty delivers data
     * before the user provides a destination buffer.
     *
     * The allocation size is rounded up to [POOL_FRIENDLY_CAPACITY] when
     * the inbound packet is smaller, so that [PooledDirectAllocator]'s
     * per-EventLoop freelist can serve the request instead of allocating a
     * fresh `DirectByteBuffer` per packet. Only the actual [readable] bytes
     * are copied; the remaining capacity stays writable but unused.
     */
    internal val handler = object : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            val byteBuf = msg as ByteBuf
            try {
                if (!byteBuf.isReadable) return

                val readable = byteBuf.readableBytes()
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

        override fun channelInactive(ctx: ChannelHandlerContext) {
            onReadClosed?.invoke()
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            onReadClosed?.invoke()
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
                val bb = pw.buf.unsafeBuffer.duplicate()
                bb.position(pw.offset)
                bb.limit(pw.offset + pw.length)
                val nettyBuf = Unpooled.wrappedBuffer(bb)
                if (i == size - 1) {
                    lastFuture = nettyChannel.writeAndFlush(nettyBuf)
                } else {
                    nettyChannel.write(nettyBuf)
                }
            }

            lastFuture?.addListener {
                for (pw in writes) pw.buf.release()
                updatePendingBytes(-totalBytes)
                callback?.invoke()
            } ?: run {
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
     * Releases all pending write buffers and closes the Netty channel.
     * Unsent data is discarded. Idempotent: subsequent calls are no-ops.
     */
    override fun close() {
        if (!opened) return
        opened = false
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
