package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.pipeline.IoTransport
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Non-suspend [IoTransport] for Netty pipeline channels.
 *
 * Buffers outbound [IoBuf] writes and flushes them via Netty's
 * [writeAndFlush][NettyNativeChannel.writeAndFlush]. Unlike NIO which
 * can write synchronously, Netty writes are always asynchronous —
 * [flush] returns `false` and completion is signalled via [onFlushComplete].
 *
 * **Write path**: batches all pending writes into Netty's outbound buffer
 * via [write][NettyNativeChannel.write], then issues a single flush on the
 * last write. The last write's [ChannelFuture] listener releases buffers
 * and invokes [onFlushComplete].
 *
 * **Buffer lifecycle**: `write()` retains the buffer. The flush completion
 * callback releases all buffers after Netty finishes sending.
 *
 * **Thread model**: `write()` and `flush()` are called from the keel pipeline.
 * Netty's EventLoop handles the actual I/O. The flush completion callback
 * runs on Netty's EventLoop thread.
 *
 * **No awaitPendingFlush**: Netty internally buffers data submitted via
 * `writeAndFlush` and processes it in EventLoop order. Even if the channel
 * is closed immediately after flush, Netty guarantees the write is processed
 * before close (same EventLoop ordering). This is the same model as
 * NWConnection (framework-managed flow control).
 */
internal class NettyIoTransport(
    private val nettyChannel: NettyNativeChannel,
) : IoTransport {

    private val pendingWrites = mutableListOf<PendingWrite>()

    override var onFlushComplete: (() -> Unit)? = null

    /**
     * Buffers [buf] for the next [flush] call.
     *
     * Captures (readerIndex, readableBytes) snapshot and retains the buffer.
     * The caller's readerIndex is advanced immediately so it can reuse the buf.
     */
    override fun write(buf: IoBuf) {
        val bytes = buf.readableBytes
        if (bytes == 0) return
        val offset = buf.readerIndex
        buf.retain()
        buf.readerIndex += bytes
        pendingWrites.add(PendingWrite(buf, offset, bytes))
    }

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

        val callback = onFlushComplete
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
            callback?.invoke()
        } ?: run {
            for (pw in writes) pw.buf.release()
        }

        return false // Always async.
    }

    /**
     * Releases all pending write buffers and closes the Netty channel.
     * Unsent data is discarded. Idempotent (Netty close is idempotent).
     */
    override fun close() {
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        // Async close — do not call sync() to avoid EventLoop deadlock.
        nettyChannel.close()
    }

    /**
     * Snapshot of a buffered write: the [IoBuf] (retained), the byte offset
     * where readable data starts, and the number of bytes to write.
     */
    internal class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)
}
