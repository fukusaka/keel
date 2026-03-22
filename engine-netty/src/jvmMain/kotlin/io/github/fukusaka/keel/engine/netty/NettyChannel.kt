package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.core.BufferAllocator
import io.github.fukusaka.keel.core.NativeBuf
import io.github.fukusaka.keel.core.SocketAddress
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFuture
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.socket.DuplexChannel
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import java.net.InetSocketAddress
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import io.github.fukusaka.keel.core.Channel as KeelChannel
import io.netty.channel.Channel as NettyNativeChannel

/**
 * Snapshot of a buffered write: the [NativeBuf] (retained), the byte offset
 * where readable data starts, and the number of bytes to write.
 */
private class PendingWrite(val buf: NativeBuf, val offset: Int, val length: Int)

/**
 * Netty-based [KeelChannel] implementation for JVM.
 *
 * **Push-to-pull bridge via coroutines**: Netty delivers data via [channelRead]
 * callbacks (push model). With `autoRead = false`, data is only read when keel
 * explicitly calls [nettyChannel.read()][NettyNativeChannel.read]. The [read]
 * method suspends via [suspendCancellableCoroutine] and resumes when the Netty
 * handler receives data.
 *
 * **Read path (copy from ByteBuf)**: Unlike kqueue/epoll/NIO which read
 * directly into [NativeBuf], Netty delivers data asynchronously before the
 * user provides a buffer. The [ByteBuf] content is copied into [NativeBuf] via
 * [ByteBuf.getBytes]. This is an accepted Phase 5 limitation — same
 * structural constraint as NWConnection's dispatch_data_t copy. Phase 6
 * will introduce MemoryOwner abstraction for zero-copy push model support.
 *
 * **Write path**: Uses PendingWrite buffering (matching KqueueChannel/NioChannel
 * pattern). [flush] batches all pending writes into Netty's outbound buffer
 * via [write][NettyNativeChannel.write], then issues a single
 * [writeAndFlush][NettyNativeChannel.writeAndFlush] on the last write.
 * The last write's [ChannelFuture] is awaited via [suspendCancellableCoroutine]
 * with a listener callback — no thread blocking.
 *
 * **Thread safety**: [pendingReadCont] is accessed from both coroutine threads
 * ([read], [close]) and Netty's worker EventLoop thread ([channelRead],
 * [channelInactive], [exceptionCaught]). All access is protected by [readLock]
 * to prevent double-resume races (e.g. [close] and [channelInactive] firing
 * concurrently).
 *
 * ```
 * Read path (auto-read=false, coroutine bridge):
 *   keel coroutine: read(buf) --> suspendCancellableCoroutine + nettyChannel.read()
 *   Netty EventLoop: channelRead(ByteBuf) --> continuation.resume(byteBuf)
 *   keel coroutine: resumed --> copy ByteBuf to NativeBuf --> release ByteBuf
 *
 * Write path (buffered, async flush):
 *   write(buf) --> retain, record PendingWrite
 *   flush()    --> write(wrappedBuffer) x N, writeAndFlush(last) + listener
 *
 * EOF detection:
 *   Netty EventLoop: channelInactive() --> continuation.resume(null)
 *   keel coroutine:  resumed with null --> return -1
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

    /**
     * Guards all access to [pendingReadCont]. Required because [read] and
     * [close] run on coroutine threads while [channelRead], [channelInactive],
     * and [exceptionCaught] run on Netty's worker EventLoop thread.
     * Coroutine [Mutex] cannot be used here because the handler callbacks
     * are not in a coroutine context ([Mutex.lock] is a suspend function).
     */
    private val readLock = Any()
    private var pendingReadCont: CancellableContinuation<ByteBuf?>? = null
    private val pendingWrites = mutableListOf<PendingWrite>()
    // @Volatile for isOpen/isActive property getters read outside readLock.
    // Writes happen on the coroutine thread (close) or EventLoop (channelInactive).
    @Volatile
    private var _open = true
    @Volatile
    private var _active = true
    private var outputShutdown = false

    override val isOpen: Boolean get() = _open
    override val isActive: Boolean get() = _active

    /** Suspends until this channel is fully closed by Netty's EventLoop. */
    override suspend fun awaitClosed() {
        if (!_open) return
        suspendCancellableCoroutine { cont ->
            nettyChannel.closeFuture().addListener {
                cont.resume(Unit)
            }
        }
    }

    /**
     * Suspends until data is available from Netty's event loop, then copies
     * the received [ByteBuf] into [buf].
     *
     * With `autoRead = false`, explicitly requests one read from Netty via
     * [NettyNativeChannel.read]. The handler's [channelRead] resumes the
     * suspended coroutine with the received [ByteBuf].
     *
     * @return number of bytes read, or -1 on EOF/channel inactive.
     */
    override suspend fun read(buf: NativeBuf): Int {
        check(_open) { "Channel is closed" }

        val byteBuf = suspendCancellableCoroutine { cont ->
            synchronized(readLock) {
                pendingReadCont = cont
                cont.invokeOnCancellation {
                    synchronized(readLock) { pendingReadCont = null }
                }
            }
            // auto-read=false: explicitly request one read from Netty.
            // Called outside readLock to avoid holding the lock during Netty I/O.
            nettyChannel.read()
        }

        // EOF: null from channelInactive, or empty buffer
        if (byteBuf == null || !byteBuf.isReadable) {
            byteBuf?.release()
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
    override suspend fun write(buf: NativeBuf): Int {
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
     * Sends all buffered writes via Netty's [writeAndFlush][NettyNativeChannel.writeAndFlush].
     *
     * Batches all pending writes into Netty's outbound buffer using
     * [write][NettyNativeChannel.write], then issues a single flush on the last
     * write. The last write's [ChannelFuture] is awaited via
     * [suspendCancellableCoroutine] — no thread blocking.
     */
    override suspend fun flush() {
        check(_open) { "Channel is closed" }
        val size = pendingWrites.size
        if (size == 0) return
        var lastFuture: ChannelFuture? = null
        for ((i, pw) in pendingWrites.withIndex()) {
            val bb = pw.buf.unsafeBuffer.duplicate()
            bb.position(pw.offset)
            bb.limit(pw.offset + pw.length)
            val nettyBuf = Unpooled.wrappedBuffer(bb)
            if (i == size - 1) {
                lastFuture = nettyChannel.writeAndFlush(nettyBuf)
            } else {
                nettyChannel.write(nettyBuf)
            }
            pw.buf.release()
        }
        pendingWrites.clear()
        // Await the last write's completion via listener callback
        lastFuture?.let { future ->
            suspendCancellableCoroutine { cont ->
                future.addListener { f ->
                    if (f.isSuccess) {
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(
                            f.cause() ?: Exception("flush failed")
                        )
                    }
                }
                cont.invokeOnCancellation { future.cancel(false) }
            }
        }
    }

    /**
     * Sends a FIN to the remote peer. The shutdown is asynchronous —
     * Netty's EventLoop handles the actual socket operation. No await
     * is needed because the caller does not depend on shutdown completion
     * (unlike flush, where the caller needs write confirmation).
     */
    override fun shutdownOutput() {
        if (!outputShutdown && _open) {
            outputShutdown = true
            if (nettyChannel is DuplexChannel) {
                nettyChannel.shutdownOutput()
            }
        }
    }

    override fun asSource(): RawSource = ChannelSource(this, allocator)

    override fun asSink(): RawSink = ChannelSink(this, allocator)

    override fun close() {
        if (_open) {
            _open = false
            _active = false
            // Resume any pending read with null (EOF), under lock to
            // prevent double-resume with channelInactive/exceptionCaught.
            synchronized(readLock) {
                pendingReadCont?.resume(null)
                pendingReadCont = null
            }
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
     * Netty [ChannelInboundHandlerAdapter] that bridges push events to
     * suspended coroutines. With `autoRead = false`, [channelRead] is only
     * called after keel explicitly calls [nettyChannel.read()][NettyNativeChannel.read].
     */
    internal val handler = object : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            synchronized(readLock) {
                val cont = pendingReadCont
                pendingReadCont = null
                if (cont != null) {
                    cont.resume(msg as ByteBuf)
                } else {
                    // auto-read=false should prevent this, but release defensively
                    (msg as ByteBuf).release()
                }
            }
        }

        override fun channelInactive(ctx: ChannelHandlerContext) {
            _active = false
            synchronized(readLock) {
                pendingReadCont?.resume(null)
                pendingReadCont = null
            }
        }

        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            synchronized(readLock) {
                pendingReadCont?.resumeWithException(cause)
                pendingReadCont = null
            }
            ctx.close()
        }
    }

    companion object {
        internal fun toSocketAddress(addr: java.net.SocketAddress?): SocketAddress? {
            val inet = addr as? InetSocketAddress ?: return null
            return SocketAddress(inet.address.hostAddress, inet.port)
        }
    }
}
