package io.github.keel.engine.netty

import io.github.keel.core.BufferAllocator
import io.github.keel.core.NativeBuf
import io.github.keel.core.SocketAddress
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DuplexChannel
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import io.github.keel.core.Channel as KeelChannel
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Snapshot of a buffered write: the [NativeBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * Netty-based [KeelChannel] implementation for JVM.
 *
 * **Push-to-pull bridge**: Netty delivers data via [channelRead] callbacks
 * (push model). This class buffers received [ByteBuf]s in a
 * [LinkedBlockingQueue] and exposes a blocking [read] (pull model).
 *
 * **Read path (copy from ByteBuf)**: Unlike kqueue/epoll/NIO which read
 * directly into [NativeBuf], Netty delivers data asynchronously before the
 * user calls [read]. The [ByteBuf] content is copied into [NativeBuf] via
 * [ByteBuf.getBytes]. This is an accepted Phase 5 limitation — same
 * structural constraint as NWConnection's dispatch_data_t copy. Phase 6
 * will introduce MemoryOwner abstraction for zero-copy push model support.
 *
 * **Write path**: Uses PendingWrite buffering (matching KqueueChannel/NioChannel
 * pattern). [flush] batches all pending writes into Netty's outbound buffer
 * via [write], then issues a single [writeAndFlush] on the last write.
 * Only the last write's future is awaited with a timeout to avoid blocking
 * Dispatchers.IO threads under high concurrency. Each [NativeBuf.unsafeBuffer]
 * is wrapped as Netty [ByteBuf] via [Unpooled.wrappedBuffer] — zero-copy on
 * the write side.
 *
 * ```
 * Read path (push → pull, copy):
 *   Netty EventLoop: channelRead(ByteBuf) --> readQueue.put(byteBuf)
 *   keel thread:     read(NativeBuf) --> readQueue.take() --> copy --> release
 *
 * Write path (buffered, batch flush with timeout):
 *   write(buf) --> retain, record PendingWrite
 *   flush()    --> write(wrappedBuffer) x N, writeAndFlush(last).await(timeout)
 *
 * EOF detection:
 *   Netty EventLoop: channelInactive() --> readQueue.put(EMPTY_BUFFER)
 *   keel thread:     read() --> take() --> EMPTY_BUFFER --> return -1
 * ```
 *
 * @param nettyChannel The underlying Netty channel.
 * @param allocator    Buffer allocator for [asSource]/[asSink] bridge.
 */
internal class NettyChannel(
    private val nettyChannel: NettyNativeChannel,
    override val allocator: BufferAllocator,
    override val remoteAddress: SocketAddress?,
    override val localAddress: SocketAddress?,
) : KeelChannel {

    private val readQueue = LinkedBlockingQueue<ByteBuf>()
    private val pendingWrites = mutableListOf<PendingWrite>()
    private var _open = true
    private var _active = true
    private var outputShutdown = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    /** Phase (a): no-op. Will suspend until closed in Phase (b). */
    override suspend fun awaitClosed() {}

    override suspend fun read(buf: NativeBuf): Int = readBlocking(buf)

    override suspend fun write(buf: NativeBuf): Int = writeBlocking(buf)

    override suspend fun flush() = flushBlocking()

    /**
     * Blocks until data is available from Netty's event loop, then copies
     * the received [ByteBuf] into [buf].
     *
     * @return number of bytes read, or -1 on EOF/channel inactive.
     */
    internal fun readBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        // Poll with timeout to avoid indefinite blocking (e.g. if channel
        // is closed concurrently). 5-second timeout matches kqueue/epoll pattern.
        val byteBuf = readQueue.poll(5, java.util.concurrent.TimeUnit.SECONDS)
            ?: return -1 // Timeout: treat as EOF

        // EOF sentinel: empty buffer from channelInactive
        if (!byteBuf.isReadable) {
            byteBuf.release()
            return -1
        }

        val readable = byteBuf.readableBytes().coerceAtMost(buf.writableBytes)
        val bb = buf.unsafeBuffer
        bb.position(buf.writerIndex)
        bb.limit(buf.writerIndex + readable)
        byteBuf.getBytes(byteBuf.readerIndex(), bb)
        buf.writerIndex += readable
        byteBuf.release()
        return readable
    }

    /**
     * Buffers a write by retaining [buf] and recording the current readable range.
     */
    internal fun writeBlocking(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }
        check(!outputShutdown) { "Output already shut down" }
        val bytes = buf.readableBytes
        if (bytes == 0) return 0
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        return bytes
    }

    /**
     * Sends all buffered writes via Netty's [writeAndFlush].
     *
     * Batches all pending writes into Netty's outbound buffer using [write],
     * then issues a single [flush]. Only the last write's future is awaited
     * with a timeout — this avoids blocking Dispatchers.IO threads on every
     * individual write, which caused thread starvation under high concurrency
     * (50+ connections with Connection: close).
     */
    internal fun flushBlocking() {
        check(_open) { "Channel is closed" }
        val size = pendingWrites.size
        for ((i, pw) in pendingWrites.withIndex()) {
            val bb = pw.buf.unsafeBuffer.duplicate()
            bb.position(pw.offset)
            bb.limit(pw.offset + pw.length)
            val nettyBuf = Unpooled.wrappedBuffer(bb)
            if (i == size - 1) {
                // Last write: flush and await with timeout to avoid indefinite blocking
                // when the client closes the connection before the write completes.
                nettyChannel.writeAndFlush(nettyBuf)
                    .await(FLUSH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            } else {
                nettyChannel.write(nettyBuf)
            }
            pw.buf.release()
        }
        pendingWrites.clear()
    }

    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            if (nettyChannel is DuplexChannel) {
                nettyChannel.shutdownOutput()
                    .await(FLUSH_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
    }

    override fun asSource(): RawSource = ChannelSource(this, allocator)

    override fun asSink(): RawSink = ChannelSink(this, allocator)

    override fun close() {
        if (_open) {
            _open = false
            _active = false
            for (pw in pendingWrites) {
                pw.buf.release()
            }
            pendingWrites.clear()
            // Do not call sync() — it deadlocks if called from the EventLoop thread.
            // Async close is sufficient; the channel will be closed by Netty's EventLoop.
            nettyChannel.close()
        }
    }

    /**
     * Netty [ChannelInboundHandlerAdapter] that bridges push events to the
     * blocking [readQueue]. Added to each accepted/connected channel's pipeline.
     */
    internal val handler = object : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            readQueue.put(msg as ByteBuf)
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            // EOF sentinel: unreadable empty buffer
            readQueue.put(Unpooled.EMPTY_BUFFER)
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            readQueue.put(Unpooled.EMPTY_BUFFER)
            ctx.close()
        }
    }

    companion object {
        /** Timeout for writeAndFlush().await() to prevent indefinite blocking. */
        private const val FLUSH_TIMEOUT_SECONDS = 5L

        internal fun toSocketAddress(addr: java.net.SocketAddress?): SocketAddress? {
            val inet = addr as? InetSocketAddress ?: return null
            return SocketAddress(inet.address.hostAddress, inet.port)
        }
    }
}
